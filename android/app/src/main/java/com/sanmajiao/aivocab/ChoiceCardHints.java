package com.sanmajiao.aivocab;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Card geometry is local metadata, never text sent to the answer model. */
final class ChoiceCardHints {
    private ChoiceCardHints() { }
    static List<ChoiceCardGeometry.Card> cards(Bitmap bitmap,List<QuestionParser.OcrLine> lines) {
        if(bitmap==null || bitmap.isRecycled()) return Collections.emptyList();
        int start=bitmap.getHeight()/7;
        for(QuestionParser.OcrLine line:lines) if(line.text.contains("返回")) start=Math.max(start,line.bottom);
        return ChoiceCardGeometry.detect(new ChoiceCardGeometry.Pixels() {
            public int width(){return bitmap.getWidth();} public int height(){return bitmap.getHeight();}
            public void row(int y,int[] row){bitmap.getPixels(row,0,row.length,0,y,row.length,1);}
        },start);
    }
    static List<QuestionParser.OcrLine> annotate(List<QuestionParser.OcrLine> lines,List<ChoiceCardGeometry.Card> cards) {
        if(cards.isEmpty()) return lines;
        // A full-width card bank must have a vocabulary stem above it. Do not turn
        // arbitrary settings panels or a fill page into a choice question.
        boolean stem=false;
        for(QuestionParser.OcrLine line:lines) if(line.bottom<cards.get(0).top
                && line.text.matches(".*[A-Za-z\\u4e00-\\u9fff].*")
                && line.top>cards.get(0).top/3 && !line.text.contains("vocabgo")) stem=true;
        if(!stem) return lines;
        List<QuestionParser.OcrLine> result=new ArrayList<>();
        boolean[] found=new boolean[cards.size()];
        for(QuestionParser.OcrLine line:lines) {
            int slot=0;
            for(int i=0;i<cards.size();i++) {
                ChoiceCardGeometry.Card card=cards.get(i);
                if(line.center()>card.top && line.center()<card.bottom
                        && line.left>=card.left-8 && line.right<=card.right+8) {slot=i+1;found[i]=true;break;}
            }
            result.add(new QuestionParser.OcrLine(line.text,line.left,line.top,line.right,line.bottom,
                    line.highlighted,line.words,slot,slot==0?0:cards.size()));
        }
        for(int i=0;i<cards.size();i++) if(!found[i]) {
            ChoiceCardGeometry.Card card=cards.get(i);
            result.add(new QuestionParser.OcrLine("[选项未识别]",card.left,card.top,card.right,card.bottom,
                    false,Collections.emptyList(),i+1,cards.size()));
        }
        return result;
    }
}
