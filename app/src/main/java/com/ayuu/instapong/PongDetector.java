package com.ayuu.instapong;
import android.graphics.Bitmap;
import android.graphics.Color;
public final class PongDetector {
 public static final class Observation{public boolean ballFound;public float ballX,ballY,ballRadius;public boolean paddleFound;public float paddleX,paddleY,paddleWidth;}
 public Observation detect(Bitmap b){Observation o=new Observation();int w=b.getWidth(),h=b.getHeight();long sx=0,sy=0,count=0;int minX=w,maxX=0,minY=h,maxY=0;int step=3;
  for(int y=(int)(h*.12f);y<(int)(h*.88f);y+=step)for(int x=8;x<w-8;x+=step){int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c),mx=Math.max(r,Math.max(g,bl)),mn=Math.min(r,Math.min(g,bl));if(r>210&&g>100&&g<230&&bl<120&&(mx-mn)>100){sx+=x;sy+=y;count++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);}}
  if(count>120&&count<12000){float cx=sx/(float)count,cy=sy/(float)count,bw=maxX-minX,bh=maxY-minY;if(bw<w*.25f&&bh<h*.16f&&bw>12&&bh>12){o.ballFound=true;o.ballX=cx;o.ballY=cy;o.ballRadius=Math.max(bw,bh)*.5f;}}
  int yStart=(int)(h*.78f),yEnd=(int)(h*.94f),bestY=-1,bestRun=0,bestStart=0,bestEnd=0;
  for(int y=yStart;y<=yEnd;y+=2){int run=0,runStart=0;for(int x=8;x<w-8;x++){int c=b.getPixel(x,y),r=Color.red(c),g=Color.green(c),bl=Color.blue(c);boolean dark=r<75&&g<75&&bl<75;if(dark){if(run==0)runStart=x;run++;}else{if(run>bestRun){bestRun=run;bestY=y;bestStart=runStart;bestEnd=x-1;}run=0;}}if(run>bestRun){bestRun=run;bestY=y;bestStart=runStart;bestEnd=w-9;}}
  if(bestRun>w*.12f&&bestRun<w*.55f){o.paddleFound=true;o.paddleX=(bestStart+bestEnd)*.5f;o.paddleY=bestY;o.paddleWidth=bestRun;}return o;
 }
}
