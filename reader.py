"""Local screen OCR and question extraction; no API calls."""
import re
import time
import statistics
from collections import Counter, deque
from dataclasses import replace
from PIL import Image, ImageOps
from core import Question, clean_text, parse_question, spatial_rows, input_line

def prepare(image, crop=True):
    if crop and image.height / image.width >= 1.8:
        # Include moved inputs; discard browser and keyboard rows by content.
        image = image.crop((0, 0, image.width, int(image.height * .70)))
    if image.width < 850:
        image = image.resize((1080, round(image.height * 1080 / image.width)), Image.Resampling.LANCZOS)
    image.thumbnail((1500, 2000))
    return image.convert('RGB')


def recover_blank_details(engine, image, result, hint_result):
    """Retry only an uncertain input/possessive line, not the whole screen."""
    rows = spatial_rows(result)
    for row in rows:
        if not re.search(r'[A-Za-z]{2,}', row['text']):
            continue
        line = input_line(image,row)
        if line is None:
            continue
        x0,x1,y = line
        height = row['height']

        def local(box, cutoff, padding=40):
            box = tuple(map(int,box))
            picture = image.crop(box).resize(((box[2]-box[0])*2,(box[3]-box[1])*2),Image.Resampling.LANCZOS)
            picture = ImageOps.expand(picture.convert('L').point(lambda v:0 if v<cutoff else 255).convert('RGB'),border=padding,fill='white')
            recovered = engine.recognize(picture)
            result['ocr_ms'] = result.get('ocr_ms',0)+recovered.get('ocr_ms',0)
            words = [dict(w) for r in recovered.get('lines',[]) for w in r.get('words',[])]
            for word in words:
                word.update(x=box[0]+(word['x']-padding)/2,y=box[1]+(word['y']-padding)/2,width=word['width']/2,height=word['height']/2)
            return words

        question = parse_question(result,image,hint_result=hint_result)
        if question and question.kind=='blank' and not question.prefix:
            # Remove the dark underline that makes the short pale hint vanish.
            words = local((x0,max(0,row['y']-height*.45),x1,y-max(3,height*.15)),218)
            letters = [w for w in words if re.fullmatch('[A-Za-z]{1,15}',w['text'])]
            if letters:
                hint_result.setdefault('lines',[]).append({'words':letters})
        if re.search(r"\b[A-Za-z]{2,}\s+[sS]\b",row['text']):
            right = min(image.width,max(w['x']+w['width'] for w in row['words'])+height*1.5)
            words = local((max(0,x0-height*.5),max(0,row['y']-height*1.2),right,min(image.height,y+height*.6)),240,0)
            for word in words:
                if not re.fullmatch(r"[A-Za-z]+['’][sS]",word['text']):
                    continue
                peers = [w for w in row['words'] if min(w['x']+w['width'],word['x']+word['width'])-max(w['x'],word['x']) > w['width']*.5]
                if re.sub('[^a-z]','',word['text'].lower()) == ''.join(re.sub('[^a-z]','',w['text'].lower()) for w in peers):
                    # Accept a restored apostrophe only when letters agree.
                    for r in result.get('lines',[]):
                        r['words'] = [w for w in r.get('words',[]) if w not in peers]
                    result.setdefault('lines',[]).append({'words':[word]})
        break


def _base_read_question(engine, image, forced='auto'):
    result = engine.recognize(image)
    question = parse_question(result, image, forced)
    if forced != 'choice' and (question is None or (question.kind == 'blank' and (not question.length or len(question.prefix) < 2))):
        # Recover pale floating hints, while retaining the normal-pass context.
        enhanced = image.convert('L').point(lambda v: 0 if v < 218 else 255).convert('RGB')
        hint_result = engine.recognize(enhanced)
        question = parse_question(result, image, forced, hint_result)
        if question is None:
            question = parse_question(hint_result, image, forced)
        result['ocr_ms'] = result.get('ocr_ms', 0) + hint_result.get('ocr_ms', 0)
        if question and question.kind == 'blank':
            recover_blank_details(engine,image,result,hint_result)
            question = parse_question(result,image,forced,hint_result)
    return question, result


def _recognize_crop(engine, image, box, cutoff=None, scale=2, padding=30):
    box = tuple(map(int, box))
    picture = image.crop(box).convert('L')
    if cutoff is not None:
        picture = picture.point(lambda v: 0 if v < cutoff else 255)
    picture = ImageOps.expand(picture.resize((picture.width*scale,picture.height*scale), Image.Resampling.LANCZOS).convert('RGB'),border=padding,fill='white')
    result = engine.recognize(picture)
    for line in result.get('lines',[]):
        for word in line.get('words',[]):
            word.update(x=box[0]+(word['x']-padding)/scale,y=box[1]+(word['y']-padding)/scale,width=word['width']/scale,height=word['height']/scale)
    return result


def _underlines(image, result):
    """Find fields even when the only English is an unrecognized short hint."""
    rows = spatial_rows(result)
    navigation = [r['y']+r['height'] for r in rows if '返回' in r['text']]
    lower = int(max(navigation,default=0)+5)
    gray=image.convert('L'); data=gray.tobytes(); width,height=gray.size
    found=[]
    for y in range(lower, min(height,int(height*.62))):
        row=data[y*width:(y+1)*width]; start=None
        for x in range(width+1):
            dark=x<width and row[x]<160
            if dark and start is None:start=x
            elif not dark and start is not None:
                if width*.14<x-start<width*.8:
                    xs=[start+int((x-start)*fraction) for fraction in (.1,.3,.7,.9)]
                    thickness=statistics.median(sum(data[yy*width+xx]<180 for yy in range(max(0,y-6),min(height,y+7))) for xx in xs)
                    if thickness<=5 and all(data[max(0,y-18)*width+xx]>230 for xx in (start,x-1)):
                        if not found or y-found[-1][2]>7:found.append((start,x,y))
                start=None
    return found


def _pale_hint(engine, image, underline, normal_height=40):
    """Read original gray hint pixels; dark user input is deliberately excluded."""
    x0,x1,y=underline
    top=max(0,int(y-normal_height*1.8)); bottom=min(image.height,int(y+normal_height*1.8))
    crop=image.crop((x0,top,x1,bottom)).convert('L'); w,h=crop.size; data=crop.tobytes()
    seen=set(); components=[]
    for index,value in enumerate(data):
        if value>=226 or index in seen:continue
        queue=deque([index]);seen.add(index);items=[]
        while queue:
            current=queue.popleft();items.append(current)
            xx,yy=current%w,current//w
            for nx,ny in ((xx-1,yy),(xx+1,yy),(xx,yy-1),(xx,yy+1)):
                if 0<=nx<w and 0<=ny<h:
                    neighbour=ny*w+nx
                    if neighbour not in seen and data[neighbour]<226:seen.add(neighbour);queue.append(neighbour)
        if len(items)<3 or statistics.median(data[v] for v in items)<145:continue
        xs=[v%w for v in items];ys=[v//w for v in items]
        components.append((min(xs),min(ys),max(xs)+1,max(ys)+1))
    dashes=[b for b in components if image.width*.006<=b[2]-b[0]<=image.width*.04 and b[3]-b[1]<=max(6,normal_height*.23) and (b[2]-b[0])/max(1,b[3]-b[1])>=2.4]
    for dash in sorted(dashes,key=lambda b:b[1]):
        center=(dash[1]+dash[3])/2
        run=sorted([b for b in dashes if abs((b[1]+b[3])/2-center)<max(4,normal_height*.15)],key=lambda b:b[0])
        if len(run)>1:
            gaps=[b[0]-a[0] for a,b in zip(run,run[1:])]
            if max(gaps)>min(gaps)*1.6:continue
        first=run[0][0]
        letters=[b for b in components if b[2]<=first-2 and first-b[2]<normal_height*8 and abs((b[1]+b[3])/2-center)<normal_height*.7 and b[3]-b[1]>normal_height*.15]
        if not letters:continue
        left=min(b[0] for b in letters); right=max(b[2] for b in letters)
        if first-right>normal_height*.7:continue
        y0=min(b[1] for b in letters);y1=max(b[3] for b in letters)
        # Include disconnected i/j dots. Losing the dot makes imp become mp/lmp.
        dots=[b for b in components if left<=b[0] and b[2]<=right and y0-normal_height*.6<=b[1]<y0 and b[3]-b[1]<normal_height*.3]
        if dots:y0=min(y0,min(b[1] for b in dots))
        bitmap=crop.crop((left,max(0,y0-3),right,min(h,y1+3))).point(lambda v:0 if v<220 else 255)
        scale=max(2,min(4,round(70/max(1,y1-y0))))
        bitmap=bitmap.resize((bitmap.width*scale,bitmap.height*scale),Image.Resampling.LANCZOS).convert('RGB')
        # The system Chinese OCR drops many isolated 2-letter words. Repeating
        # exactly the same pixels supplies enough Latin text for recognition.
        canvas=Image.new('RGB',(max(500,5*(bitmap.width+20)+80),bitmap.height+100),'white')
        for i in range(5):canvas.paste(bitmap,(40+i*(bitmap.width+20),40))
        canvas=ImageOps.expand(canvas,border=50,fill='white')
        if getattr(engine,'backend','windows')=='rapid' and hasattr(engine,'recognize_line'):
            active=ImageOps.invert(canvas.convert('L')).getbbox()
            recognized=engine.recognize_line(canvas.crop(active))
        else:
            recognized=engine.recognize(canvas)
            if not re.search(r'[A-Za-z0\[|丨]',recognized.get('text','')) and hasattr(engine,'recognize_line'):
                active=ImageOps.invert(canvas.convert('L')).getbbox()
                recognized=engine.recognize_line(canvas.crop(active))
        normalized=recognized.get('text','').replace('0','o').replace('[','l').replace('|','l').replace('丨','i')
        tokens=re.findall(r'\b[A-Za-z]{1,15}\b',normalized)
        counts=Counter(t.lower() for t in tokens)
        joined=''.join(tokens).lower()
        prefix=''
        for repetitions in (5,4):
            if joined and len(joined)%repetitions==0:
                unit=joined[:len(joined)//repetitions]
                if 1<=len(unit)<=15 and unit*repetitions==joined:prefix=unit;break
        if not prefix:
            if not counts:continue
            prefix,count=counts.most_common(1)[0]
            if count<3:continue
        return prefix,len(prefix)+len(run),(x0+left,top+y0,right-left,y1-y0),recognized.get('ocr_ms',0)
    return '',None,None,0


def _choice_regions(image, result):
    rows=spatial_rows(result);gray=image.convert('L');data=gray.tobytes();width,height=gray.size
    start_y=int(max((r['y']+r['height'] for r in rows if '返回' in r['text']),default=0))
    left=(int(width*.025),int(width*.085));right=(int(width*.915),int(width*.975))
    bands=[];start=None
    for y in range(start_y,height):
        row=data[y*width:(y+1)*width]
        ink=min(row[left[0]:left[1]],default=255)<237 and min(row[right[0]:right[1]],default=255)<237
        if ink and start is None:start=y
        elif not ink and start is not None:
            if y-start>35:bands.append((start,y))
            start=None
    return bands if len(bands) in (3,4) else []


def _read_single(engine,image,region):
    """Single-line model: tight ink crop avoids line-detection omissions."""
    region=tuple(map(int,region))
    if region[2]<=region[0] or region[3]<=region[1]:return '',None,0
    picture=image.crop(region).convert('L')
    background=picture.getpixel((min(picture.width-1,10),picture.height//2))
    invert=background<220
    mask=picture.point(lambda v:255 if (v>220 if invert else v<210) else 0)
    bbox=mask.getbbox()
    if bbox is None:return '',None,0
    if invert:picture=picture.point(lambda v:255-v)
    picture=picture.crop(bbox).convert('RGB')
    if not hasattr(engine,'recognize_line'):return '',None,0
    recognized=engine.recognize_line(picture)
    if recognized.get('confidence',0)<.50:return '',None,recognized.get('ocr_ms',0)
    text=clean_text(recognized.get('text',''))
    word={'text':text,'x':region[0]+bbox[0],'y':region[1]+bbox[1],'width':bbox[2]-bbox[0],'height':bbox[3]-bbox[1]}
    return text,word,recognized.get('ocr_ms',0)


def read_question(engine, image, forced='auto'):
    started=time.monotonic();result=engine.recognize(image);passes=1
    question=parse_question(result,image,forced)
    underlines=_underlines(image,result) if forced!='choice' else []
    if underlines:
        underline=underlines[0]
        rows=spatial_rows(result)
        nearby=[r['height'] for r in rows if abs(r['y']-underline[2])<120 and r['height']>15]
        font_height=statistics.median(nearby or [40])
        prefix,length,box,ms=_pale_hint(engine,image,underline,font_height)
        original_hint=None
        if len(prefix)<2 and getattr(engine,'backend','windows')=='windows':
            original_hint,extra=_base_read_question(engine,image,forced)
            result['ocr_ms']=result.get('ocr_ms',0)+extra.get('ocr_ms',0)
            if original_hint and original_hint.kind=='blank' and len(original_hint.prefix)>len(prefix) and original_hint.length:
                prefix,length=original_hint.prefix,original_hint.length
        result['ocr_ms']=result.get('ocr_ms',0)+ms;passes+=1
        # Re-read the full question line and Chinese meaning locally, excluding
        # browser and keyboard. This also restores possessives and short hints.
        region=(0,max(0,underline[2]-font_height*2),image.width,min(image.height,underline[2]+font_height*3.4))
        local=_recognize_crop(engine,image,region,218,2)
        result['ocr_ms']+=local.get('ocr_ms',0);passes+=1
        if box:
            x,y,w,h=box
            local.setdefault('lines',[]).append({'words':[{'text':prefix,'x':x,'y':y,'width':w,'height':h}]})
        candidate=parse_question(local,image,forced,hint_result=local)
        if candidate and candidate.kind=='blank':
            if question and question.kind=='blank':
                if len(re.sub('[^a-z]','',question.context))>len(re.sub('[^a-z]','',candidate.context)):
                    candidate=replace(candidate,context=question.context)
                if len(re.findall('[\u4e00-\u9fff]',question.prompt))>len(re.findall('[\u4e00-\u9fff]',candidate.prompt)):
                    candidate=replace(candidate,prompt=question.prompt)
            question=candidate
        elif question is None:
            result['lines']=local.get('lines',[])
        if question and question.kind=='blank':
            if len(re.findall('[\u4e00-\u9fff]',question.prompt))<2:
                question=None
                result['recognition_passes']=passes
                return question,result
            # Preserve small horizontal Chinese glyphs such as 一 in the local
            # pass; they are real meaning text, not input dashes.
            question=replace(question,prefix=prefix,length=length,prefix_alternatives=(),warnings=() if prefix and length else ('原始字母提示未确认，可手动补充前缀与长度',))
            result['input_bbox']=(underline[0],max(0,underline[2]-font_height*1.8),underline[1],underline[2]+4)
            result['hint_confirmed']=bool(prefix and length)
        # Reconstruct static left/right context separately; an OCR line box
        # crossing the field must never swallow one's / sth. / surrounding words.
        left,left_word,left_ms=_read_single(engine,image,(0,max(0,underline[2]-font_height*1.8),underline[0]-3,underline[2]-3))
        right,right_word,right_ms=_read_single(engine,image,(underline[1]+3,max(0,underline[2]-font_height*1.8),image.width,underline[2]-3))
        if question and question.kind=='blank' and '____' in question.context:
            previous_left,previous_right=question.context.split('____',1)
            if not left:left=previous_left.strip()
            if not right:right=previous_right.strip()
        result['ocr_ms']+=left_ms+right_ms
        meanings=[r for r in rows if underline[2]+5<r['y']<underline[2]+font_height*3.5 and len(re.findall('[\u4e00-\u9fff]',r['text']))>=2 and not re.search('回答|答案|输入正确|输入错误',r['text'])]
        recovered_meanings=[];final_lines=[]
        for row in meanings:
            text,word,ms=_read_single(engine,image,(image.width*.03,row['y']-5,image.width*.97,row['y']+row['height']+5))
            result['ocr_ms']+=ms
            if len(re.findall('[\u4e00-\u9fff]',text))>=2:
                recovered_meanings.append(text)
                final_lines.append({'text':text,'words':[word]})
        if recovered_meanings and (left or right or prefix or question):
            prompt=clean_text(' '.join(recovered_meanings))
            # Floating hint letters overlay the meaning but are not its text.
            prompt=re.sub(r"[A-Za-z]+[-_—－\s]*",'',prompt).strip()
            if original_hint and original_hint.kind=='blank' and original_hint.length:
                prompt=original_hint.prompt
            context=clean_text(' '.join((left,'____',right))).lower()
            question=Question('blank',prompt,context=context,prefix=prefix,length=length,warnings=() if prefix and length else ('原始字母提示未确认，可手动补充前缀与长度',))
            field={'text':prefix or '____','x':underline[0]+5,'y':underline[2]-font_height,'width':max(1,underline[1]-underline[0]-10),'height':font_height}
            words=[w for w in (left_word,field,right_word) if w]
            result['lines']=[{'text':context,'words':words}]+final_lines
            result['input_bbox']=(underline[0],max(0,underline[2]-font_height*1.8),underline[1],underline[2]+4)
            result['hint_confirmed']=bool(prefix and length)
    if forced!='blank' and (question is None or question.kind=='choice'):
        bands=_choice_regions(image,result)
        if bands:
            rebuilt={'lines':[],'text':''};texts=[]
            body_top=max((r['y']+r['height'] for r in spatial_rows(result) if '返回' in r['text']),default=max(0,bands[0][0]-180))
            regions=[(width*.08,body_top+8,width*.92,bands[0][0]-12)] if (width:=image.width) else []
            box_height=statistics.median(y1-y0 for y0,y1 in bands)
            regions += [(width*.10,(y0+y1-box_height)/2+5,width*.90,(y0+y1+box_height)/2-5) for y0,y1 in bands]
            for region in regions:
                if region[3]<=region[1]:continue
                text,word,ms=_read_single(engine,image,region)
                result['ocr_ms']=result.get('ocr_ms',0)+ms;passes+=1
                if text and word:
                    texts.append(text);rebuilt['lines'].append({'text':text,'words':[word]})
                    continue
                recovered=_recognize_crop(engine,image,region,None,2)
                result['ocr_ms']=result.get('ocr_ms',0)+recovered.get('ocr_ms',0);passes+=1
                rebuilt['lines'].extend(recovered.get('lines',[]))
                texts.append(clean_text(recovered.get('text','')))
            if len(texts)==len(bands)+1 and all(texts) and len(re.findall('[\u4e00-\u9fff]',texts[0]))>=2 and all(re.fullmatch(r"[A-Za-z]+(?:[ '\-][A-Za-z]+)*",text) for text in texts[1:]):
                question=Question('choice',texts[0],tuple(texts[1:]))
                result['lines']=rebuilt['lines']
                result['recognition_passes']=passes
                result['local_elapsed_ms']=round((time.monotonic()-started)*1000)
                return question,result
            candidate=parse_question(rebuilt,image,forced)
            if candidate and candidate.kind=='choice' and len(candidate.options)==len(bands):question=candidate
            elif len(bands) in (3,4) and (not question or len(question.options)!=len(bands)):question=None
    result['recognition_passes']=passes
    result['local_elapsed_ms']=round((time.monotonic()-started)*1000)
    return question,result
