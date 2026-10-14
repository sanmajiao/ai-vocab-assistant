package com.sanmajiao.aivocab;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ChoiceCardGeometryTest {
    private static final class Image implements ChoiceCardGeometry.Pixels {
        final int w=500,h=950; final int[] data=new int[w*h];
        Image(){Arrays.fill(data,0xfff7f7f7);}
        public int width(){return w;} public int height(){return h;}
        public void row(int y,int[] output){System.arraycopy(data,y*w,output,0,w);}
        void rectangle(int l,int t,int r,int b){
            for(int x=l;x<r;x++){data[t*w+x]=0xffdddddd;data[(b-1)*w+x]=0xffdddddd;}
            for(int y=t;y<b;y++){data[y*w+l]=0xffdddddd;data[y*w+r-1]=0xffdddddd;}
        }
    }
    @Test public void fourLargeCardsHaveFourPhysicalPositions(){
        Image image=new Image();for(int i=0;i<4;i++)image.rectangle(25,250+i*90,475,310+i*90);
        List<ChoiceCardGeometry.Card> cards=ChoiceCardGeometry.detect(image,120);
        assertEquals(4,cards.size()); assertEquals(250,cards.get(0).top);assertEquals(580,cards.get(3).bottom);
    }
    @Test public void threeActualCardsRemainSupported(){
        Image image=new Image();for(int i=0;i<3;i++)image.rectangle(25,250+i*90,475,310+i*90);
        assertEquals(3,ChoiceCardGeometry.detect(image,120).size());
    }
    @Test public void dividersWithoutSideEdgesAreNotCards(){
        Image image=new Image();for(int i=0;i<8;i++)for(int x=25;x<475;x++)image.data[(200+i*35)*image.w+x]=0xffdddddd;
        assertTrue(ChoiceCardGeometry.detect(image,120).isEmpty());
    }
    @Test public void smallGridButtonsAreNotChoiceCards(){
        Image image=new Image();for(int i=0;i<3;i++){
            image.rectangle(25,250+i*90,230,310+i*90);image.rectangle(270,250+i*90,475,310+i*90);
        }
        assertTrue(ChoiceCardGeometry.detect(image,120).isEmpty());
    }
    @Test public void greenFeedbackWhiteLetterGapsStillCountOneCard(){
        Image image=new Image();for(int i=0;i<4;i++)image.rectangle(25,250+i*90,475,310+i*90);
        for(int y=340;y<400;y++)for(int x=25;x<475;x++)image.data[y*image.w+x]=0xff00c65c;
        // A horizontal slice missing due to white feedback lettering must not split its slot.
        for(int x=200;x<280;x++)image.data[365*image.w+x]=0xffffffff;
        assertEquals(4,ChoiceCardGeometry.detect(image,120).size());
    }
    @Test public void unrelatedBottomActionPanelDoesNotJoinAnswerBank(){
        Image image=new Image();for(int i=0;i<4;i++)image.rectangle(25,250+i*90,475,310+i*90);
        for(int y=800;y<875;y++)for(int x=25;x<475;x++)image.data[y*image.w+x]=0xffdddddd;
        assertEquals(4,ChoiceCardGeometry.detect(image,120).size());
    }
    @Test public void twoVisibleCardsAreNotACompleteThreeCardBank(){
        Image image=new Image();image.rectangle(25,250,475,310);image.rectangle(25,340,475,400);
        assertTrue(ChoiceCardGeometry.detect(image,120).isEmpty());
    }
}
