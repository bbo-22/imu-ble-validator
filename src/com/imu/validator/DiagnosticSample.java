package com.imu.validator;

import java.util.Locale;

/** Immutable latest-valid sample and click-time context. No Android or disk dependency. */
public final class DiagnosticSample {
    public final long wallMs, monoNs, attempt;
    public final String session;
    public final double angle1, angle2, angle3, accelA, accelB, accelC, norm;
    public DiagnosticSample(FrameParser.Frame f, long wallMs, long monoNs, long attempt, String session) {
        this.wallMs=wallMs;this.monoNs=monoNs;this.attempt=attempt;this.session=session;
        angle1=f.angle;angle2=f.angle2;angle3=f.angle3;
        accelA=f.accelA;accelB=f.accelB;accelC=f.accelC;norm=f.accelNorm;
    }
    public static final class Context {
        public final DiagnosticSample sample;
        public final long attempt, timeoutMs;
        public final boolean connected, subscribed, lastPacketValid;
        public Context(DiagnosticSample s,long a,long t,boolean c,boolean n,boolean v) {
            sample=s;attempt=a;timeoutMs=t;connected=c;subscribed=n;lastPacketValid=v;
        }
        public String invalidReason(long clickNs) {
            if(!connected)return "disconnected";
            if(!subscribed)return "not_subscribed";
            if(sample==null)return "no_valid_sample";
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
                "angle3_deg_candidate",valid?sample.angle3:null};
        }
    }
    public String formatted() {
        return String.format(Locale.ROOT,
            "angle1 Roll候选  %8.2f °\nangle2 Pitch候选 %8.2f °\nangle3 Yaw候选   %8.2f °\n\n加速度A        %8.2f g\n加速度B        %8.2f g\n加速度C        %8.2f g\n加速度向量模长 %8.2f g",angle1,angle2,angle3,accelA,accelB,accelC,norm);
    }
    public static String invalidDisplay() {
        return "angle1 Roll候选       失效\nangle2 Pitch候选      失效\nangle3 Yaw候选        失效\n\n加速度A              失效\n加速度B              失效\n加速度C              失效\n加速度向量模长       失效";
    }
    public static String display(Context context,long nowNs) {
        return context.invalidReason(nowNs).isEmpty()?context.sample.formatted():invalidDisplay();
    }
}
