package com.imu.validator;
/** Pure Java decoder for the observed notification format. No compensation. */
public final class FrameParser {
 public static final class Frame {
  public String hex, error="", unknownPrefix="", unknown23to26="";
  public boolean valid, checksumOk;
  public int angleRaw, counter, checksumExpected, checksumRaw;
  public double angle;
 }
 public static String hex(byte[] b) { StringBuilder s=new StringBuilder(); for(byte v:b) s.append(String.format(java.util.Locale.ROOT,"%02x",v&255)); return s.toString(); }
 public static byte[] bytes(String s) { byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16); return b; }
 public static int uint16(byte[] b,int i) { return (b[i]&255)|((b[i+1]&255)<<8); }
 public static int delta(int previous,int current) { return (current-previous)&65535; }
 public static Frame parse(byte[] b) {
  Frame f=new Frame(); f.hex=hex(b);
  if(b.length!=30) { f.error="length_not_30"; return f; }
  if((b[0]&255)!=0x68) f.error="header_not_68;";
  if((b[1]&255)!=0x1d) f.error+="observed_format_byte1_not_1d;";
  for(int i=1;i<29;i++) f.checksumExpected+=b[i]&255;
  f.checksumExpected&=255; f.checksumRaw=b[29]&255; f.checksumOk=f.checksumExpected==f.checksumRaw;
  if(!f.checksumOk) f.error+="checksum_mismatch;";
  f.angleRaw=(short)uint16(b,5); f.angle=f.angleRaw/100.0; f.counter=uint16(b,27);
  f.unknownPrefix=f.hex.substring(4,10); f.unknown23to26=f.hex.substring(46,54); f.valid=f.error.isEmpty(); return f;
 }
}
