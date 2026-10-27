package com.imu.validator;

import java.util.Locale;

/** Immutable latest-valid sample and click-time context. No Android or disk dependency. */
public final class DiagnosticSample {
    public final long wallMs, monoNs, attempt;
    public final String session;
    public final double angle1, angle2, angle3, accelA, accelB, accelC, norm;
    public final double rollDeg,pitchDeg,yawDeg;
    public final String rawHex,error;
    public final boolean protocolValid;
    public final int rollRaw,pitchRaw,yawRaw;
    public DiagnosticSample(FrameParser.Frame f, long wallMs, long monoNs, long attempt, String session) {
        this.wallMs=wallMs;this.monoNs=monoNs;this.attempt=attempt;this.session=session;
        angle1=f.angle;angle2=f.angle2;angle3=f.angle3;
        rollDeg=angle1;pitchDeg=angle2;yawDeg=angle3;
        rawHex=f.hex;error=f.error;protocolValid=f.valid;
        rollRaw=f.angleRaw;pitchRaw=f.angle2Raw;yawRaw=f.angle3Raw;
        accelA=f.accelA;accelB=f.accelB;accelC=f.accelC;norm=f.accelNorm;
    }
    public static final class Context {
        public final DiagnosticSample sample;
        public final long attempt, timeoutMs;
        public final boolean connected, subscribed, lastPacketValid;
        public final String session;
        public Context(DiagnosticSample s,long a,long t,boolean c,boolean n,boolean v) {
            this(s,a,t,c,n,v,s==null?"":s.session);
        }
        public Context(DiagnosticSample s,long a,long t,boolean c,boolean n,boolean v,String session) {
            sample=s;attempt=a;timeoutMs=t;connected=c;subscribed=n;lastPacketValid=v;
            this.session=session;
        }
        public String invalidReason(long clickNs) {
            if(!connected)return "disconnected";
            if(!subscribed)return "not_subscribed";
            if(sample==null)return "no_valid_sample";
            if(!sample.protocolValid)return "invalid_sample";
            if(!sample.session.equals(session))return "different_session";
            if(sample.attempt!=attempt)return "different_attempt";
            if(!lastPacketValid)return "latest_packet_invalid";
            if(clickNs<sample.monoNs)return "sample_after_click";
            if(clickNs-sample.monoNs>=timeoutMs*1000000L)return "data_timeout";
            return "";
        }
        public Object[] marker(String experiment,String action,long wall,long mono) {
            String reason=invalidReason(mono);boolean valid=reason.isEmpty();
            return new Object[]{"experiment_name",experiment,"action",action,"click_wall_time_ms",wall,
                "click_monotonic_ns",mono,"data_valid",valid,"invalid_reason",reason,
                "sample_wall_time_ms",sample==null?null:sample.wallMs,
                "sample_monotonic_ns",sample==null?null:sample.monoNs,
                "sample_age_ms",sample==null?null:(mono-sample.monoNs)/1000000.0,
                "sample_attempt_id",sample==null?null:sample.attempt,
                "sample_session_id",sample==null?null:sample.session,
                "angle1_deg_candidate",valid?sample.angle1:null,
                "angle2_deg_candidate",valid?sample.angle2:null,
                "angle3_deg_candidate",valid?sample.angle3:null,
                "roll_deg",valid?sample.rollDeg:null,"pitch_deg",valid?sample.pitchDeg:null,"yaw_deg",valid?sample.yawDeg:null};
        }
    }
    public String formatted() {
        return String.format(Locale.ROOT,
            "Roll  (angle1)  %8.2f °\nPitch (angle2)  %8.2f °\nYaw   (angle3)  %8.2f °\n相对偏航角Yaw（本次连接）\n\n加速度A        %8.2f g\n加速度B        %8.2f g\n加速度C        %8.2f g\n加速度向量模长 %8.2f g",rollDeg,pitchDeg,yawDeg,accelA,accelB,accelC,norm);
    }
    public static String invalidDisplay() {
        return "Roll  (angle1)       失效\nPitch (angle2)       失效\nYaw   (angle3)       失效\n相对偏航角Yaw（本次连接）\n\n加速度A              失效\n加速度B              失效\n加速度C              失效\n加速度向量模长       失效";
    }
    public static String display(Context context,long nowNs) {
        return context.invalidReason(nowNs).isEmpty()?context.sample.formatted():invalidDisplay();
    }
}
