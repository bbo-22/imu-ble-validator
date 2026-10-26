package com.imu.validator;
/** Monotonic timestamps in milliseconds; validity never depends on wall clock. */
public final class ReadingState {
 public boolean connected, subscribed, lastPacketValid;
 public long validCount, invalidCount, checksumErrors, first=-1,last=-1,lastValid=-1;
 public FrameParser.Frame latest;
 public void receive(FrameParser.Frame f,long now) {
  if(first<0) first=now; last=now; latest=f; lastPacketValid=f.valid;
  if(f.valid) { validCount++; lastValid=now; } else { invalidCount++; if(f.error.contains("checksum")) checksumErrors++; }
 }
 public boolean live(long now,long timeout) { return connected&&subscribed&&lastPacketValid&&lastValid>=0&&now-lastValid<timeout; }
 public void disconnect() { connected=false; subscribed=false; }
 public double frequency(long now) { long span=now-first; return first>=0&&span>0 ? (validCount+invalidCount-1)*1000.0/span : 0; }
}
