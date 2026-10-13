from __future__ import annotations

import json
import threading
import time

import httpx

from core import SYSTEM_PROMPT, Question, validate_answer


class Expired(Exception):
    pass


class RetryableError(RuntimeError):
    """A transient failure that can be retried within the same question budget."""


class DeepSeek:
    def __init__(self, key: str, model: str = 'deepseek-flash', transport=None):
        self.key, self.model = key, model
        self.label = '模拟接口' if transport is not None else 'DeepSeek'
        self.client = httpx.Client(
            base_url='https://api.deepseek.com',
            headers={'Authorization': 'Bearer ' + key},
            limits=httpx.Limits(max_connections=4, max_keepalive_connections=4),
            timeout=httpx.Timeout(8, connect=1.5),
            transport=transport,
        )

    def models(self):
        response = self.client.get('/models', timeout=6)
        self._check(response)
        return [m['id'] for m in response.json().get('data', [])]

    @staticmethod
    def _check(response):
        if response.status_code == 401:
            raise RuntimeError('API Key 无效，请检查后重新保存')
        if response.status_code == 402:
            raise RuntimeError('DeepSeek 账户余额不足')
        if response.status_code == 429:
            raise RetryableError('DeepSeek 当前限流')
        if response.status_code >= 500:
            raise RetryableError(f'DeepSeek 暂时不可用（HTTP {response.status_code}）')
        if response.status_code >= 400:
            raise RuntimeError(f'DeepSeek 请求失败（HTTP {response.status_code}）')

    def answer(self, question: Question, deadline: float, cancel: threading.Event):
        remaining = deadline - time.monotonic()
        if remaining <= .15 or cancel.is_set():
            raise Expired()
        body = {
            'model': self.model,
            'messages': [{'role': 'system', 'content': SYSTEM_PROMPT}, {'role': 'user', 'content': json.dumps(question.payload(), ensure_ascii=False, separators=(',', ':'))}],
            'thinking': {'type': 'disabled'},
            'max_tokens': 80,
            'stream': True,
        }
        attempt_budget = min(remaining, 2.1 if question.kind == 'choice' else 3.0)
        timeout = httpx.Timeout(attempt_budget, connect=min(1.0, attempt_budget), pool=min(.2, attempt_budget))
        content = ''
        try:
            with self.client.stream('POST', '/chat/completions', json=body, timeout=timeout) as response:
                self._check(response)
                for line in response.iter_lines():
                    if cancel.is_set() or time.monotonic() >= deadline:
                        raise Expired()
                    if not line.startswith('data:'):
                        continue
                    data = line[5:].strip()
                    if data == '[DONE]':
                        break
                    try:
                        chunk = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    if chunk.get('error'):
                        raise RuntimeError('DeepSeek 返回错误，未显示答案')
                    choices = chunk.get('choices') or []
                    if not choices:
                        continue
                    content += choices[0].get('delta', {}).get('content') or ''
                    # Return as soon as the complete answer arrives; discard
                    # reasoning tokens and do not wait for trailing usage data.
                    if '}' in content:
                        try:
                            json.loads(content.strip().removeprefix('```json').removesuffix('```').strip())
                        except json.JSONDecodeError:
                            continue
                        return validate_answer(question, content)
            if cancel.is_set() or time.monotonic() >= deadline:
                raise Expired()
            return validate_answer(question, content)
        except httpx.TimeoutException:
            raise RetryableError('API 暂时未响应') from None
        except httpx.HTTPError:
            raise RetryableError('无法连接 DeepSeek，请检查电脑网络') from None

    def close(self):
        self.client.close()
