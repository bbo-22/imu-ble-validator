package com.imu.validator;
/** Pure Java decoder for the observed notification format. No compensation. */
public final class FrameParser {
 public static final class Frame {
  public String hex, error="", unknownPrefix="", unknown23to26="";
  public boolean valid, checksumOk;
  public int length, angleRaw, angle2Raw, angle3Raw, accelARaw, accelBRaw, accelCRaw, counter, checksumExpected, checksumRaw;
  public double angle, angle2, angle3, accelA, accelB, accelC, accelNorm;
  public int rollRaw,pitchRaw,yawRaw;
  public double rollDeg,pitchDeg,yawDeg;
 }
 public static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b) s.append(String.format(java.util.Locale.ROOT,"%02x",v&255)); return s.toString(); }
 public static byte[] bytes(String s) { byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16); return b; }
 public static int uint16(byte[] b,int i) { return (b[i]&255)|((b[i+1]&255)<<8); }
 public static int delta(int previous,int current) { return (current-previous)&65535; }
 public static double shortestAngleDelta(double reference,double current) { return ((current-reference+180)%360+360)%360-180; }
 public static Frame parse(byte[] b) {
  Frame f=new Frame(); f.hex=hex(b); f.length=b.length;
  if(b.length!=30) { f.error="length_not_30"; return f; }
  if((b[0]&255)!=0x68) f.error="header_not_68;";
  if((b[1]&255)!=0x1d) f.error+="observed_format_byte1_not_1d;";
  for(int i=1;i<29;i++) f.checksumExpected+=b[i]&255;
  f.checksumExpected&=255; f.checksumRaw=b[29]&255; f.checksumOk=f.checksumExpected==f.checksumRaw;
  if(!f.checksumOk) f.error+="checksum_mismatch;";
  f.angleRaw=(short)uint16(b,5); f.angle=f.angleRaw/100.0; f.counter=uint16(b,27);
  f.angle2Raw=(short)uint16(b,7);f.angle3Raw=(short)uint16(b,9);
  f.accelARaw=(short)uint16(b,11);f.accelBRaw=(short)uint16(b,13);f.accelCRaw=(short)uint16(b,15);
  f.angle2=f.angle2Raw/100.0;f.angle3=f.angle3Raw/100.0;
  f.rollRaw=f.angleRaw;f.pitchRaw=f.angle2Raw;f.yawRaw=f.angle3Raw;
  f.rollDeg=f.angle;f.pitchDeg=f.angle2;f.yawDeg=f.angle3;
  f.accelA=f.accelARaw/1000.0;f.accelB=f.accelBRaw/1000.0;f.accelC=f.accelCRaw/1000.0;
  f.accelNorm=Math.sqrt(f.accelA*f.accelA+f.accelB*f.accelB+f.accelC*f.accelC);
  f.unknownPrefix=f.hex.substring(4,10); f.unknown23to26=f.hex.substring(46,54); f.valid=f.error.isEmpty(); return f;
 }
}
