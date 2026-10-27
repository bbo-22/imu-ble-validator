package com.imu.validator;

/** Single-field diagnostic only, never an absolute heading or a 3D relative pose. */
public final class YawComparison {
    public final boolean valid;
    public final Double deltaDeg;
    public final String reason;
    private YawComparison(Double value,String reason){this.deltaDeg=value;this.reason=reason;valid=reason.isEmpty();}
    public static YawComparison between(DiagnosticSample reference,DiagnosticSample current){
        if(reference==null||current==null)return new YawComparison(null,"missing_sample");
        if(!reference.session.equals(current.session)||reference.attempt!=current.attempt)
            return new YawComparison(null,"cross_connection_yaw_reference_unavailable");
        if(!reference.protocolValid||!current.protocolValid)return new YawComparison(null,"invalid_sample");
        // Conservative diagnostic guard, not a vendor-specified singularity threshold.
        if(Math.abs(reference.pitchDeg)>=85||Math.abs(current.pitchDeg)>=85)
            return new YawComparison(null,"pitch_near_90_roll_yaw_coupling");
        return new YawComparison(FrameParser.shortestAngleDelta(reference.yawDeg,current.yawDeg),"");
    }
}
