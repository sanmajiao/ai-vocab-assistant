package com.sanmajiao.aivocab;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class QuestionParserTest {
    private static final String OWN_PERIL_LOG = """
            [36,168,73,205] X
            [75,48,171,79] 15:22
            [59,289,185,335] く返回
            [247,945,350,983] issue
            [247,1105,351,1145] one's
            [235,2236,310,2273] 跳过
            [496,142,580,185] 练习
            [643,41,686,58] 99.9
            [449,623,632,665] 自担风险
            [643,66,687,81] KB/s
            [412,205,666,236] appvocabgo.com
            [763,949,801,981] at
            [737,1110,826,1150] peril
            """;

    @Test public void realPossessiveGridKeepsEveryCandidateInScreenOrder() {
        Question q = QuestionParser.parse(boxes(OWN_PERIL_LOG));
        assertNotNull(q); assertEquals("order", q.kind); assertEquals("自担风险", q.prompt);
        assertEquals(Arrays.asList("issue", "at", "one's", "peril"), q.options);
        assertEquals(0, q.slotCount); // Slot pixels are attached by the capture pipeline.
    }

    @Test public void realPossessiveGridDoesNotRewriteChineseOcrTypo() {
        Question q = QuestionParser.parse(boxes(OWN_PERIL_LOG.replace("自担风险", "自坦风险")));
        assertNotNull(q); assertEquals("order", q.kind); assertEquals("自坦风险", q.prompt);
        assertEquals(Arrays.asList("issue", "at", "one's", "peril"), q.options);
    }

    @Test public void smartPossessiveApostropheKeepsOneCompleteCandidate() {
        Question q = QuestionParser.parse(boxes(OWN_PERIL_LOG.replace("one's", "one’s")));
        assertNotNull(q); assertEquals("order", q.kind);
        assertEquals(Arrays.asList("issue", "at", "one's", "peril"), q.options);
    }

    @Test public void mergedPossessiveGridCountsSpacedCellsRatherThanSplittingApostrophe() {
        QuestionParser.OcrLine issue = new QuestionParser.OcrLine("issue", 247, 945, 350, 983);
        QuestionParser.OcrLine at = new QuestionParser.OcrLine("at", 763, 949, 801, 981);
        QuestionParser.OcrLine own = new QuestionParser.OcrLine("one’s", 247, 1105, 351, 1145);
        QuestionParser.OcrLine peril = new QuestionParser.OcrLine("peril", 737, 1110, 826, 1150);
        Question q = parse(new QuestionParser.OcrLine("自担风险", 449, 623, 632, 665),
                new QuestionParser.OcrLine("issue at", 247, 945, 801, 983, false, Arrays.asList(issue, at)),
                new QuestionParser.OcrLine("one’s peril", 247, 1105, 826, 1150, false, Arrays.asList(own, peril)));
        assertEquals("order", q.kind);
        assertEquals(Arrays.asList("issue", "at", "one's", "peril"), q.options);
    }

    @Test public void compoundAndContractionRemainIndependentWordBankCells() {
        Question q = parse(line("词语组合", 100),
                new QuestionParser.OcrLine("self-reliant", 30, 240, 180, 264),
                new QuestionParser.OcrLine("can't", 300, 240, 380, 264),
                new QuestionParser.OcrLine("be", 30, 330, 70, 354),
                new QuestionParser.OcrLine("sth.", 300, 330, 360, 354));
        assertEquals("order", q.kind);
        assertEquals(Arrays.asList("self-reliant", "can't", "be", "sth."), q.options);
    }

    @Test public void possessiveSentenceElementsWithNormalGapsDoNotBecomeOrdering() {
        QuestionParser.OcrLine at = new QuestionParser.OcrLine("at", 30, 100, 60, 124);
        QuestionParser.OcrLine own = new QuestionParser.OcrLine("one's", 70, 100, 140, 124);
        QuestionParser.OcrLine ownRisk = new QuestionParser.OcrLine("own", 150, 100, 200, 124);
        QuestionParser.OcrLine peril = new QuestionParser.OcrLine("peril", 210, 100, 280, 124);
        assertNull(QuestionParser.parse(Arrays.asList(
                new QuestionParser.OcrLine("at one's own peril", 30, 100, 280, 124, false,
                        Arrays.asList(at, own, ownRisk, peril)), line("自担风险", 180))));
    }

    @Test public void incompleteVerticalPossessiveChoiceIsStillRejected() {
        assertNull(QuestionParser.parse(Arrays.asList(line("自担风险", 100),
                line("one's", 230), line("peril", 330))));
    }

    private static QuestionParser.OcrLine card(String text,int slot,int count,int y) {
        return new QuestionParser.OcrLine(text,30,y,430,y+24,false,
                java.util.Collections.emptyList(),slot,count);
    }
    @Test public void fourPhysicalCardsMissingFirstCannotCompressVisibleIndex() {
        assertNull(QuestionParser.parse(Arrays.asList(line("Prejudice can misguide our minds.",100),
                card("[选项未识别]",1,4,240),card("v. 误导;引入歧途",2,4,320),
                card("v. 承诺;保证",3,4,400),card("v. 压扁;压碎",4,4,480))));
    }
    @Test public void fourthPhysicalCardIsStillFourthAfterRecovery() {
        Question q=parse(line("He had time to reflect on his successes.",100),
                card("v. 反映;表现",1,4,240),card("v. 从事;承担",2,4,320),
                card("v. 压碎;压扁",3,4,400),card("v. 沉思;深思",4,4,480));
        assertEquals("v. 沉思;深思",q.options.get(3));assertEquals(4,q.options.size());
    }
    @Test public void chineseSentenceTranslationIsContextInsteadOfChoiceOption() {
        Question q=parse(line("Listen to criticism but don't be crushed by it.",100),
                line("要听取批评意见,但不要被它击垮。",180),card("v. 陪伴;陪同",1,4,280),
                card("v. 压扁;压碎",2,4,360),card("v. 破坏;摧毁(信心、希望等)",3,4,440),
                card("v. 承担;从事",4,4,520));
        assertEquals("choice",q.kind);assertEquals(4,q.options.size());
        assertEquals("要听取批评意见,但不要被它击垮。",q.context);
        assertFalse(q.options.toString().contains("要听取"));
    }
    @Test public void trueThreePhysicalCardsRemainThreeChoices() {
        Question q=parse(line("织布机",100),card("loom",1,3,240),card("zone",2,3,320),card("nest",3,3,400));
        assertEquals(Arrays.asList("loom","zone","nest"),q.options);
    }
    @Test public void mixedGarbageCardCannotBeAcceptedAsCompleteFourChoiceBank() {
        assertNull(QuestionParser.parse(Arrays.asList(line("We negotiate a contract.",100),
                card("v. 从事;承担",1,4,240),card("v. 谈判;协商",2,4,320),
                card("v. 弄乱;搅混",3,4,400),card("(8) , #A",4,4,480))));
    }
    @Test public void ordinaryChineseMeaningSelectsEnglishWithoutMarkedWord() {
        Question q=parse(line("n. 教务主任",100),card("registrar",1,4,240),card("loan",2,4,320),
                card("peril",3,4,400),card("faculty",4,4,480));
        assertEquals("n. 教务主任",q.prompt);assertEquals("",q.target);assertEquals("",q.context);
        assertEquals(Arrays.asList("registrar","loan","peril","faculty"),q.options);
    }
    @Test public void ordinaryEnglishWordSelectsChineseWithoutSentenceRequirement() {
        Question q=parse(line("faculty",100),card("n. 全体教师",1,4,240),card("n. 少年时代",2,4,320),
                card("n. 本科生",3,4,400),card("n. 注册主任",4,4,480));
        assertEquals("faculty",q.prompt);assertEquals(4,q.options.size());assertEquals("",q.target);
    }
    @Test public void wrappedContextualChoiceKeepsGreenTargetAndChineseSentenceContext() {
        QuestionParser.OcrLine marked=new QuestionParser.OcrLine("loans",200,100,270,124,true,java.util.Collections.emptyList());
        Question q=parse(new QuestionParser.OcrLine("The public library loans books",30,100,430,124,false,Arrays.asList(marked)),
                line("to people.",140),line("公共图书馆向人们借出图书。",180),
                card("v. 陪伴",1,4,280),card("v. 借出",2,4,360),card("n. 借款",3,4,440),card("v. 摧毁",4,4,520));
        assertEquals("loans",q.target);assertEquals("The public library loans books to people.",q.prompt);
        assertEquals("公共图书馆向人们借出图书。",q.context);assertEquals("v. 借出",q.options.get(1));
    }
    private static final String PERIL_LOG = """
            [33,166,73,206] X
            [75,46,171,79] 10:23
            [60,292,187,335] く返回
            [496,142,580,185] 练习
            [235,2236,310,2273] 跳过
            [591,41,633,58] 15.0
            [591,66,634,81] KB/s
            [412,201,664,237] app.vocabgo.com
            [59,471,975,520] The refugees were constantly in peril of
            [58,543,495,594] dying from hunger.
            [383,705,692,760] n. 事故;事件
            [384,871,685,923] n. 险境;危险
            [406,1033,668,1084] n.少年时代
            [402,1196,675,1244] n.大学本科生
            """;
    private static final String LOANS_LOG = """
            [36,168,73,205] X
            [79,43,228,82] 10:26 G
            [60,292,187,335] く返回
            [496,142,578,185] 练习
            [594,38,988,80] 739 9pl 73
            [233,2235,310,2272] 跳过
            [412,201,664,237] app.vocabgo.com
            [59,471,988,520] The public library loans books to people.
            [402,633,670,684] v陪伴;陪同
            [458,803,613,842] V.借出
            [219,962,859,1007] v摧毁(信心、希望等),破坏
            [383,1120,691,1172] n. 借款;货款
            """;
    private static final String LOAN_MULTI_LOG = """
            [33,166,73,206] X
            [75,41,230,78] 10:28 G
            [60,292,187,335] く返回
            [58,547,488,598] together with loan
            [60,472,874,519] Choose the words commonly used
            [90,904,248,938] student
            [496,142,580,185] 练习
            [412,205,666,236] appvocabgo.com
            [60,671,804,712] 提示:本题有2个答案,选中2个即可通过。
            [60,741,553,778] 剩余机会:2 还需选中:2
            [90,1046,179,1093] light
            [235,2234,309,2273] |跳过
            [591,37,634,54] 5.92
            [591,62,634,77] KB/s
            [329,1056,441,1092] repay
            [396,901,598,943] memories
            [749,904,881,937] attend
            """;
    private static final String ORDER_FOUR_LOG = """
            [36,168,73,205] X
            [79,46,179,79] 10:30
            [60,292,187,335] く返回
            [262,1061,333,1095] sth.
            [279,1228,316,1260] to
            [233,2235,310,2272] 跳过
            [496,142,580,185] 练习
            [594,42,639,58] 3.44
            [594,66,639,81] KB/s
            [412,202,664,237] app.vocabgo.com
            [420,737,657,779] 承诺做菜事
            [757,1061,806,1095] do
            [678,1226,886,1260] undertake
            """;

    private static List<QuestionParser.OcrLine> boxes(String log) {
        List<QuestionParser.OcrLine> result = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\[(\\d+),(\\d+),(\\d+),(\\d+)\\] ([^\\r\\n]+)").matcher(log);
        while (matcher.find()) result.add(new QuestionParser.OcrLine(matcher.group(5),
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))));
        return result;
    }
    private static void markTarget(List<QuestionParser.OcrLine> boxes, String stem, String target) {
        // Raw diagnostic logs contain line boxes, not element boxes/colors. The
        // target color is verified from the supplied screenshot; this fixture
        // attaches that metadata without claiming logged ML Kit element geometry.
        for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).text.equals(stem)) {
            QuestionParser.OcrLine line = boxes.get(i);
            QuestionParser.OcrLine word = new QuestionParser.OcrLine(target, line.left, line.top, line.right, line.bottom,
                    true, java.util.Collections.emptyList());
            boxes.set(i, new QuestionParser.OcrLine(line.text, line.left, line.top, line.right, line.bottom, false, Arrays.asList(word)));
            return;
        }
        fail("Expected real stem line");
    }
    @Test public void realPerilRawLogPreservesTwoLineSentenceAndFourChineseOptions() {
        List<QuestionParser.OcrLine> input = boxes(PERIL_LOG);
        Question q = QuestionParser.parse(input);
        assertNotNull(q); assertEquals("choice", q.kind);
        assertEquals("The refugees were constantly in peril of dying from hunger.", q.prompt);
        assertEquals(Arrays.asList("n. 事故;事件", "n. 险境;危险", "n.少年时代", "n.大学本科生"), q.options);
        markTarget(input, "The refugees were constantly in peril of", "peril");
        q = QuestionParser.parse(input); assertEquals("peril", q.target); assertEquals("choice", q.kind);
    }
    @Test public void realLoansRawLogUsesGreenVerbAndExactFourOptionTexts() {
        List<QuestionParser.OcrLine> input = boxes(LOANS_LOG);
        markTarget(input, "The public library loans books to people.", "loans");
        Question q = QuestionParser.parse(input);
        assertNotNull(q); assertEquals("choice", q.kind); assertEquals("loans", q.target);
        assertEquals("The public library loans books to people.", q.prompt);
        assertEquals(Arrays.asList("v陪伴;陪同", "V.借出", "v摧毁(信心、希望等),破坏", "n. 借款;货款"), q.options);
    }
    @Test public void realLoanMultiRawLogPreservesFiveGridCellsAndTwoSelections() {
        Question q = QuestionParser.parse(boxes(LOAN_MULTI_LOG));
        assertNotNull(q); assertEquals("multi-choice", q.kind); assertEquals("loan", q.target);
        assertEquals(2, q.minSelections);
        assertEquals(Arrays.asList("student", "memories", "attend", "light", "repay"), q.options);
        assertFalse(q.prompt.contains("跳过")); assertFalse(q.needsReview());
    }
    @Test public void realLoanRemainingCountWithColonWorksWithoutEarlierHint() {
        List<QuestionParser.OcrLine> input = boxes(LOAN_MULTI_LOG);
        input.removeIf(line -> line.text.startsWith("提示:"));
        Question q = QuestionParser.parse(input);
        assertNotNull(q); assertEquals("multi-choice", q.kind); assertEquals(2, q.minSelections);
        assertEquals("loan", q.target); assertEquals(5, q.options.size());
    }
    @Test public void realFourTokenOrderingKeepsBaseWordsAndOcrMeaningUnchanged() {
        Question q = QuestionParser.parse(boxes(ORDER_FOUR_LOG));
        assertNotNull(q); assertEquals("order", q.kind);
        assertEquals("承诺做菜事", q.prompt); // No semantic rewriting of the OCR text.
        assertEquals(Arrays.asList("sth.", "do", "to", "undertake"), q.options);
        assertFalse(q.needsReview());
    }
    @Test public void explicitEnhancedTypoIsNotSilentlyChangedToUndertake() {
        List<QuestionParser.OcrLine> input = boxes(ORDER_FOUR_LOG);
        for (int i = 0; i < input.size(); i++) if (input.get(i).text.equals("undertake")) {
            QuestionParser.OcrLine line = input.get(i);
            input.set(i, new QuestionParser.OcrLine("undertalke", line.left, line.top, line.right, line.bottom));
        }
        Question q = QuestionParser.parse(input);
        assertNotNull(q); assertEquals("order", q.kind); assertEquals("undertalke", q.options.get(3));
    }
    @Test public void syntheticContextualEnglishSynonymKeepsMarkedWordAndExactOptions() {
        // No real synonym screenshot was supplied: this is a parser structure fixture.
        QuestionParser.OcrLine target = new QuestionParser.OcrLine("peril", 260, 120, 330, 144,
                true, java.util.Collections.emptyList());
        Question q = parse(new QuestionParser.OcrLine("We knew we were in peril.", 30, 120, 430, 144,
                false, Arrays.asList(target)), line("danger", 230), line("trust", 330),
                line("wealth", 430), line("comfort", 530), line("|跳过", 800));
        assertEquals("choice", q.kind); assertEquals("peril", q.target);
        assertEquals("We knew we were in peril.", q.prompt);
        assertEquals(Arrays.asList("danger", "trust", "wealth", "comfort"), q.options);
    }
    @Test public void chineseMeaningWithFourVerticalEnglishChoicesIsNotOrdering() {
        Question q = parse(line("危险", 120), line("danger", 230), line("trust", 330),
                line("wealth", 430), line("comfort", 530));
        assertEquals("choice", q.kind); assertEquals(4, q.options.size());
    }
    /** Complete twelve OCR boxes from the phone's boy/friends failure, unchanged. */
    private static List<QuestionParser.OcrLine> realBoyPhoneBoxes() {
        return Arrays.asList(new QuestionParser.OcrLine("21:52", 67, 46, 163, 77),
                new QuestionParser.OcrLine("X", 36, 168, 73, 205),
                new QuestionParser.OcrLine("く返回", 60, 292, 187, 335),
                new QuestionParser.OcrLine("儿时的朋友", 60, 590, 284, 636),
                new QuestionParser.OcrLine("boy- - -- friends", 124, 466, 524, 525),
                new QuestionParser.OcrLine("跳过", 234, 2235, 311, 2272),
                new QuestionParser.OcrLine("屏幕共享防护中", 555, 37, 860, 84),
                new QuestionParser.OcrLine("练习", 496, 141, 579, 185),
                new QuestionParser.OcrLine("app.vocabgo.com", 412, 202, 664, 237),
                new QuestionParser.OcrLine("提交", 507, 900, 572, 932),
                new QuestionParser.OcrLine("87", 965, 52, 997, 74),
                new QuestionParser.OcrLine("三", 985, 287, 1043, 337));
    }
    @Test public void realBoyTwelveBoxesIgnoreDistantMenuBeforeMergingRows() {
        Question q = QuestionParser.parse(realBoyPhoneBoxes());
        assertNotNull(q); assertEquals("blank", q.kind); assertEquals("儿时的朋友", q.prompt);
        assertEquals("____ friends", q.context); assertEquals("boy", q.prefix); assertEquals(7, q.length);
        assertTrue(q.options.isEmpty()); assertFalse(q.needsReview());
    }
    @Test public void realRegWithDistantRightMenuRemainsReviewableBlank() {
        List<QuestionParser.OcrLine> boxes = new java.util.ArrayList<>(realRegPhoneBoxes());
        boxes.add(new QuestionParser.OcrLine("三", 985, 287, 1043, 337));
        Question q = QuestionParser.parse(boxes);
        assertNotNull(q); assertEquals("blank", q.kind);
        assertEquals("(大学)注册主任,教务主任", q.prompt);
        assertEquals("reg [缺失位置未确认: ____]", q.context);
        assertEquals("", q.prefix); assertEquals(0, q.length); assertTrue(q.needsReview());
    }
    @Test public void realBoyToolbarCannotPolluteManualBlankFallback() {
        Question q = QuestionParser.manualBlank(realBoyPhoneBoxes(), "");
        assertNotNull(q); assertEquals("儿时的朋友", q.prompt);
        assertTrue(q.context.contains("friends")); assertFalse(q.context.contains("X"));
        assertFalse(q.context.contains("返回")); assertFalse(q.prompt.contains("三"));
        assertFalse(q.prompt.contains("跳过")); assertEquals("", q.prefix); assertEquals(0, q.length);
    }
    @Test public void greenSentenceFixtureWithActualBoyToolbarStillHasFourChineseChoices() {
        // Toolbar coordinates are real; the sentence/option boxes are the existing
        // green-target parser fixture placed below it, not a claim of real ML Kit OCR.
        List<QuestionParser.OcrLine> boxes = new java.util.ArrayList<>(realBoyPhoneBoxes());
        boxes.removeIf(box -> box.text.equals("boy- - -- friends") || box.text.equals("儿时的朋友"));
        boxes.add(new QuestionParser.OcrLine("I was very", 124, 400, 524, 430));
        QuestionParser.OcrLine green = new QuestionParser.OcrLine("embarrassed", 124, 450, 310, 480,
                true, java.util.Collections.emptyList());
        boxes.add(new QuestionParser.OcrLine("embarrassed when I arrived", 124, 450, 800, 480,
                false, Arrays.asList(green)));
        boxes.add(new QuestionParser.OcrLine("late for class.", 124, 500, 600, 530));
        boxes.add(new QuestionParser.OcrLine("不好意思的", 60, 590, 284, 636));
        boxes.add(new QuestionParser.OcrLine("高兴的", 60, 665, 284, 711));
        boxes.add(new QuestionParser.OcrLine("昂贵的", 60, 740, 284, 786));
        boxes.add(new QuestionParser.OcrLine("忙碌的", 60, 815, 284, 861));
        Question q = QuestionParser.parse(boxes);
        assertNotNull(q); assertEquals("choice", q.kind); assertEquals("embarrassed", q.target);
        assertEquals("I was very embarrassed when I arrived late for class.", q.prompt);
        assertEquals(Arrays.asList("不好意思的", "高兴的", "昂贵的", "忙碌的"), q.options);
    }
    @Test public void confirmedFourFacDashesAreSevenButThreeObservedDashesAreNotInvented() {
        // The semantic word faculty must not override the actual count in OCR/pixel evidence.
        List<QuestionParser.OcrLine> boxes = new java.util.ArrayList<>(realBoyPhoneBoxes());
        boxes.set(3, new QuestionParser.OcrLine("全体教员", 60, 590, 284, 636));
        boxes.set(4, new QuestionParser.OcrLine("fac- - --", 124, 466, 524, 525));
        Question q = QuestionParser.parse(boxes);
        assertNotNull(q); assertEquals("fac", q.prefix); assertEquals(7, q.length); assertEquals("____", q.context);
        boxes.set(4, new QuestionParser.OcrLine("fac- - -", 124, 466, 524, 525));
        q = QuestionParser.parse(boxes);
        assertNotNull(q); assertEquals("fac", q.prefix); assertEquals(6, q.length);
    }
    /** Complete merged boxes copied verbatim from the phone's 0.1.1 diagnostic. */
    private static List<QuestionParser.OcrLine> realRegPhoneBoxes() {
        return Arrays.asList(new QuestionParser.OcrLine("屏幕共享防护中 (75", 576, 37, 972, 81),
                new QuestionParser.OcrLine("20:15", 68, 48, 166, 79),
                new QuestionParser.OcrLine("练习", 496, 141, 579, 184),
                new QuestionParser.OcrLine("X", 36, 168, 73, 205),
                new QuestionParser.OcrLine("app.vocabgo.com", 412, 201, 664, 237),
                new QuestionParser.OcrLine("く返回", 59, 289, 185, 335),
                new QuestionParser.OcrLine("reg", 117, 487, 180, 521),
                new QuestionParser.OcrLine("(大学)注册主任,教务主任", 89, 592, 653, 639),
                new QuestionParser.OcrLine("提交", 507, 900, 572, 932),
                new QuestionParser.OcrLine("跳过", 234, 2235, 311, 2272));
    }
    @Test public void realPhoneRegWithoutDashesIsReviewableBlankNotNavigationChoice() {
        Question q = QuestionParser.parse(realRegPhoneBoxes());
        assertNotNull(q); assertEquals("blank", q.kind);
        assertEquals("(大学)注册主任,教务主任", q.prompt);
        assertEquals("", q.prefix); assertEquals(0, q.length); assertTrue(q.needsReview());
        assertTrue(q.options.isEmpty()); assertFalse(q.context.contains("返回"));
        assertFalse(q.context.contains("X")); assertTrue(q.context.contains("reg"));
    }
    @Test public void mixedLanguageRowsCannotBecomeChineseTranslationOptions() {
        assertNull(QuestionParser.parse(Arrays.asList(line("X", 100), line("退出图标", 220),
                line("reg", 340), line("(大学)注册主任,教务主任", 460))));
    }
    @Test public void confirmedPixelDashHintWinsOverUnknownStandaloneToken() {
        List<QuestionParser.OcrLine> boxes = new java.util.ArrayList<>(realRegPhoneBoxes());
        boxes.set(6, new QuestionParser.OcrLine("reg------", 117, 487, 441, 521));
        Question q = QuestionParser.parse(boxes);
        assertNotNull(q); assertEquals("blank", q.kind); assertEquals("reg", q.prefix);
        assertEquals(9, q.length); assertEquals("____", q.context); assertFalse(q.needsReview());
    }
    @Test public void wholeSingleWordHintMayBeMissingWithChineseAndSubmit() {
        Question q = parse(line("(大学)注册主任,教务主任", 592), line("提交", 900));
        assertEquals("blank", q.kind); assertEquals("____", q.context);
        assertEquals("", q.prefix); assertEquals(0, q.length); assertTrue(q.needsReview());
    }
    @Test public void unicodeNavigationAndSharingNoiseCannotLeakIntoBody() {
        Question q = parse(line("20:15", 5), line("屏幕共享防护中", 55), line("(75", 65),
                line("app.vocabgo.com", 90), line("练习", 140), line("X", 168), line("く返回", 289),
                line("reg", 487), line("(大学)注册主任,教务主任", 592), line("提交", 900));
        assertEquals("blank", q.kind); assertEquals("(大学)注册主任,教务主任", q.prompt);
        assertEquals("reg [缺失位置未确认: ____]", q.context);
    }
    @Test public void realPhoneAdditionalLatinHeaderFragmentCannotPolluteBlank() {
        List<QuestionParser.OcrLine> boxes = new java.util.ArrayList<>(realRegPhoneBoxes());
        boxes.add(new QuestionParser.OcrLine("(75", 942, 52, 973, 74));
        Question q = QuestionParser.parse(boxes);
        assertNotNull(q); assertEquals("blank", q.kind);
        assertEquals("(大学)注册主任,教务主任", q.prompt);
        assertEquals("reg [缺失位置未确认: ____]", q.context);
        assertEquals("", q.prefix); assertEquals(0, q.length);
    }
    @Test public void wrappedSentenceRetainsGreenTargetAndFourChineseOptions() {
        QuestionParser.OcrLine green = new QuestionParser.OcrLine("embarrassed", 100, 130, 220, 154, true, java.util.Collections.emptyList());
        Question q = parse(line("词义选择练习", 20), line("I was very", 100),
                new QuestionParser.OcrLine("embarrassed when I arrived", 30, 130, 430, 154, false, Arrays.asList(green)),
                line("late for class.", 160), line("不好意思的", 240), line("高兴的", 320), line("昂贵的", 400), line("忙碌的", 480));
        assertEquals("choice", q.kind); assertEquals("embarrassed", q.target);
        assertTrue(q.prompt.contains("late for class.")); assertEquals(4, q.options.size());
    }
    @Test public void sevenCellCollocationKeepsVisibleBaseAndMinimum() {
        Question q = parse(line("Choose words commonly used together with", 100), line("undergraduate", 135),
                line("选中3个即可", 180), new QuestionParser.OcrLine("course", 30, 250, 110, 274),
                new QuestionParser.OcrLine("memories", 200, 250, 320, 274),
                new QuestionParser.OcrLine("education", 30, 320, 150, 344),
                new QuestionParser.OcrLine("degree", 220, 320, 330, 344),
                new QuestionParser.OcrLine("student", 30, 390, 140, 414),
                new QuestionParser.OcrLine("project", 220, 390, 330, 414), line("medicine", 460));
        assertEquals("multi-choice", q.kind); assertEquals("undergraduate", q.target);
        assertEquals(3, q.minSelections);
        assertEquals(Arrays.asList("course", "memories", "education", "degree", "student", "project", "medicine"), q.options);
    }
    @Test public void unlabeledFiveCellWordBankIsOrdering() {
        Question q = parse(line("不好意思做某事", 100),
                new QuestionParser.OcrLine("to", 30, 240, 70, 264),
                new QuestionParser.OcrLine("embarrassed", 160, 240, 310, 264),
                new QuestionParser.OcrLine("do", 30, 330, 70, 354),
                new QuestionParser.OcrLine("sth.", 160, 330, 220, 354), line("be", 420));
        assertEquals("order", q.kind); assertEquals(Arrays.asList("to", "embarrassed", "do", "sth.", "be"), q.options);
    }
    @Test public void unlabeledOrderingHandlesMlkitMergedGridLines() {
        QuestionParser.OcrLine to = new QuestionParser.OcrLine("to", 30, 240, 70, 264);
        QuestionParser.OcrLine embarrassed = new QuestionParser.OcrLine("embarrassed", 160, 240, 310, 264);
        QuestionParser.OcrLine doWord = new QuestionParser.OcrLine("do", 30, 330, 70, 354);
        QuestionParser.OcrLine sth = new QuestionParser.OcrLine("sth.", 160, 330, 220, 354);
        Question q = parse(line("不好意思做某事", 100),
                new QuestionParser.OcrLine("to embarrassed", 30, 240, 310, 264, false, Arrays.asList(to, embarrassed)),
                new QuestionParser.OcrLine("do sth.", 30, 330, 220, 354, false, Arrays.asList(doWord, sth)), line("be", 420));
        assertEquals("order", q.kind); assertEquals(Arrays.asList("to", "embarrassed", "do", "sth.", "be"), q.options);
    }
    @Test public void observedComSevenAndRegNineKeepExactConstraints() {
        Question combine = parse(line("com---- with", 100), line("将…结合", 180), line("提交", 300));
        assertEquals("com", combine.prefix); assertEquals(7, combine.length); assertEquals("____ with", combine.context);
        Question registrar = parse(line("reg------", 100), line("大学注册主任", 180), line("提交", 300));
        assertEquals("reg", registrar.prefix); assertEquals(9, registrar.length);
    }
    @Test public void manualPromptSupportsSingleWordWhenAllPaleHintsMissing() {
        Question q = QuestionParser.manualBlank(Arrays.asList(line("提交", 300)), "大学注册主任");
        assertNotNull(q); assertEquals("大学注册主任", q.prompt); assertEquals("____", q.context);
        assertEquals("", q.prefix); assertEquals(0, q.length); assertTrue(q.needsReview());
    }
    private static QuestionParser.OcrLine line(String text, int y) {
        return new QuestionParser.OcrLine(text, 30, y, 430, y + 24);
    }
    private static Question parse(QuestionParser.OcrLine... lines) {
        Question question = QuestionParser.parse(Arrays.asList(lines));
        assertNotNull("Expected a usable question", question);
        return question;
    }

    @Test public void threeChoiceLoomHasCorrectOrder() {
        Question q = parse(line("返回 Unit 2", 30), line("09:41", 5), line("织布机", 100),
                line("loom", 190), line("zone", 280), line("nest", 370), line("提交", 460));
        assertEquals("choice", q.kind);
        assertEquals("织布机", q.prompt);
        assertEquals(Arrays.asList("loom", "zone", "nest"), q.options);
        assertFalse(q.needsReview());
    }

    @Test public void fourChoicesReverseTranslationAndLabels() {
        Question q = parse(line("loom", 100), line("A. 织布机", 180), line("B. 区域", 260),
                line("C. 鸟巢", 340), line("D. 就业", 420));
        assertEquals(Arrays.asList("织布机", "区域", "鸟巢", "就业"), q.options);
    }

    @Test public void expandsOnesHorizonsWithSeparateHint() {
        Question q = parse(line("____ one ’ s horizons", 110), line("拓展某人的视野", 180),
                line("ex - - - -", 240), line("提交", 360));
        assertEquals("blank", q.kind);
        assertEquals("____ one's horizons", q.context);
        assertEquals("ex", q.prefix);
        assertEquals(6, q.length);
        assertFalse(q.needsReview());
    }

    @Test public void opponentInlineHintCountsDashes() {
        Question q = parse(line("a worthy opp-----", 110), line("势均力敌的对手", 180));
        assertEquals("a worthy ____", q.context);
        assertEquals("opp", q.prefix);
        assertEquals(8, q.length);
    }

    @Test public void splitUnsortedSpatialBoxesAndDashedSpaces() {
        Question q = parse(line("势均力敌的对手", 180),
                new QuestionParser.OcrLine("- - -", 240, 111, 300, 123),
                new QuestionParser.OcrLine("worthy", 55, 100, 135, 124),
                new QuestionParser.OcrLine("opp", 160, 101, 205, 125),
                new QuestionParser.OcrLine("a", 30, 100, 44, 124),
                new QuestionParser.OcrLine("- -", 210, 112, 235, 122));
        assertEquals("a worthy ____", q.context);
        assertEquals("opp", q.prefix);
        assertEquals(8, q.length);
    }

    @Test public void keyboardAndStatusCannotBecomeOptions() {
        Question q = parse(line("词达人", 30), line("返回", 60), line("a worthy opp-----", 140),
                line("势均力敌的对手", 220), line("q w e r t y u i o p", 430),
                line("a s d f g h j k l", 480), line("space", 540), line("联想候选", 590));
        assertEquals("势均力敌的对手", q.prompt);
        assertEquals("a worthy ____", q.context);
        assertFalse(q.context.contains("space"));
    }

    @Test public void missingHintStaysUnknownAndDoesNotUseContextWord() {
        Question q = parse(line("a worthy ____", 140), line("势均力敌的对手", 220));
        assertEquals("", q.prefix);
        assertEquals(0, q.length);
        assertTrue(q.needsReview());
        assertEquals("a worthy ____", q.context);
    }

    @Test public void ordinaryChoiceReturnIsNotKeyboardKey() {
        Question q = parse(line("返回归来", 100), line("return", 190), line("loom", 280), line("nest", 370));
        // A bare navigation label is filtered, but a vocabulary meaning containing
        // the same characters must not truncate the entire screen.
        assertEquals(Arrays.asList("return", "loom", "nest"), q.options);
    }

    @Test public void unusableOrIncompleteQuestionDoesNotGuess() {
        assertNull(QuestionParser.parse(Arrays.asList(line("提交", 100), line("q w e r t y", 200))));
        assertNull(QuestionParser.parse(Arrays.asList(line("织布机", 100), line("loom", 190), line("nest", 280))));
        assertNull(QuestionParser.parse(Arrays.asList(line("a worthy", 100), line("势均力敌的对手", 190))));
    }

    @Test public void omittedPaleHintWithSubmitIsReviewableBlankForManualHints() {
        Question q = parse(line("one's horizons", 110), line("拓展某人的视野", 180), line("提交", 300));
        assertEquals("blank", q.kind);
        assertEquals("", q.prefix);
        assertEquals(0, q.length);
        assertTrue(q.context.contains("one's horizons"));
        assertTrue(q.context.contains("[缺失位置未确认: ____]"));
        assertTrue(q.needsReview());
        Question manual = new Question(q.kind, q.prompt, q.context, "ex", 6, q.options);
        assertEquals("ex", manual.prefix);
        assertEquals(6, manual.length);
        assertTrue("Manual hints cannot confirm the missing box location", manual.needsReview());
    }

    @Test public void overlappingHintOnChineseRowPreservesObservedConstraints() {
        Question q = parse(line("a growing", 200), line("日益显著的趋势 tr - - -", 250), line("提交", 350));
        assertEquals("blank", q.kind);
        assertEquals("日益显著的趋势", q.prompt);
        assertEquals("tr", q.prefix);
        assertEquals(5, q.length);
        assertEquals("a growing [缺失位置未确认: ____]", q.context);
        assertTrue(q.needsReview());
        assertNull(QuestionParser.parse(Arrays.asList(line("a growing", 200),
                line("日益显著的趋势 tr - - -", 250))));
    }
}
