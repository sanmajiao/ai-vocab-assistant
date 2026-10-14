package com.sanmajiao.aivocab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Confirms large vertical answer cards. Does not infer missing words from meaning. */
final class ChoiceCardGeometry {
    interface Pixels { int width(); int height(); void row(int y, int[] pixels); }
    static final class Card {
        final int left, top, right, bottom;
        Card(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
    }
    private ChoiceCardGeometry() { }
    static List<Card> detect(Pixels image, int startY) {
        int w=image.width(), h=image.height();
        if (w<100 || h<100) return Collections.emptyList();
        List<Card> bands=new ArrayList<>(), cards=new ArrayList<>();
        int[] row=new int[w]; int bandTop=-1, bandLeft=0, bandRight=0;
        for (int y=Math.max(0,startY); y<h; y++) {
            image.row(y,row); int left=-1, right=-1, count=0;
            for(int x=w/30;x<w-w/30;x++) if(ink(row[x])) {
                if(left<0) left=x; right=x+1; count++;
            }
            boolean edge=count>=w*.73 && left>=w*.025 && right<=w*.975;
            if(edge) {
                int gap=0;
                for(int x=left;x<right;x++) {
                    gap=ink(row[x])?0:gap+1;
                    if(gap>w*.045) {edge=false;break;}
                }
            }
            if(edge) { if(bandTop<0){bandTop=y;bandLeft=left;bandRight=right;} }
            else if(bandTop>=0){ bands.add(new Card(bandLeft,bandTop,bandRight,y));bandTop=-1; }
        }
        if(bandTop>=0) bands.add(new Card(bandLeft,bandTop,bandRight,h));
        // White lettering/check marks can split a colored feedback card into
        // adjacent bands. Join only near-touching thick bands, never card gaps.
        for(int i=0;i+1<bands.size();) {
            Card a=bands.get(i),b=bands.get(i+1);
            if(b.top-a.bottom<=Math.max(2,w/100) && (a.bottom-a.top>8 || b.bottom-b.top>8)
                    && Math.abs(a.left-b.left)<w*.035 && Math.abs(a.right-b.right)<w*.035) {
                bands.set(i,new Card(Math.min(a.left,b.left),a.top,Math.max(a.right,b.right),b.bottom));
                bands.remove(i+1);
            } else i++;
        }
        for(int i=0;i<bands.size();i++) {
            Card top=bands.get(i); int thick=top.bottom-top.top;
            if(thick>=w*.065 && thick<=w*.16) {cards.add(top);continue;}
            if(thick>Math.max(8,w/90) || i+1>=bands.size()) continue;
            Card bottom=bands.get(i+1); int height=bottom.bottom-top.top;
            if(height<w*.065 || height>w*.16 || bottom.bottom-bottom.top>Math.max(8,w/90)
                    || Math.abs(top.left-bottom.left)>w*.025 || Math.abs(top.right-bottom.right)>w*.025) continue;
            // Both sides must exist at the middle; a pair of dividers is not a card.
            image.row((top.bottom+bottom.top)/2,row);
            if(!nearEdge(row,Math.min(top.left,bottom.left),w/40)
                    || !nearEdge(row,Math.max(top.right,bottom.right)-1,w/40)) continue;
            cards.add(new Card(Math.min(top.left,bottom.left),top.top,
                    Math.max(top.right,bottom.right),bottom.bottom)); i++;
        }
        // This detector intentionally excludes small grid buttons (ordering/collocations).
        List<Card> group=new ArrayList<>();
        for(Card c:cards) {
            if(!group.isEmpty()) {
                Card previous=group.get(group.size()-1),first=group.get(0);int gap=c.top-previous.bottom;
                if(gap<0 || gap>w*.12 || Math.abs(c.left-previous.left)>w*.025
                        || Math.abs(c.right-previous.right)>w*.025
                        || Math.abs(c.bottom-c.top-(first.bottom-first.top))>w*.035) {
                    if(group.size()==3 || group.size()==4) return group;
                    group=new ArrayList<>();
                }
            }
            group.add(c);
        }
        return group.size()==3 || group.size()==4?group:Collections.emptyList();
    }
    private static boolean nearEdge(int[] row,int x,int radius) {
        for(int at=Math.max(0,x-radius);at<Math.min(row.length,x+radius+1);at++) if(ink(row[at])) return true;
        return false;
    }
    private static boolean ink(int color) {
        int r=color>>16&255,g=color>>8&255,b=color&255;
        int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        return max-min<18 && min>=135 && max<=243 || max-min>50 && max<250 && min<150;
    }
}
