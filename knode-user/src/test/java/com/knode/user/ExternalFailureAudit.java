package com.knode.user;

import java.util.*;

/** Safe failure details: never retain exception messages, response bodies, tokens or causes. */
final class ExternalFailureAudit {
    private Map<String,Object> primary;
    private final List<Map<String,Object>> cleanup=new ArrayList<>();

    private static String label(String value) {
        return value!=null && value.matches("[A-Za-z0-9_.-]{1,100}")?value:"unknown";
    }
    static Map<String,Object> describe(String step,String stage,Throwable failure) {
        var result=new LinkedHashMap<String,Object>();
        result.put("step",label(step));result.put("stage",label(stage));
        result.put("type",failure.getClass().getName());
        var location=Arrays.stream(failure.getStackTrace()).filter(x -> x.getClassName().startsWith("com.knode.user.")).findFirst();
        if (location.isPresent()) {
            var frame=location.get();result.put("class",frame.getClassName());
            result.put("method",frame.getMethodName());result.put("line",frame.getLineNumber());
        } else {result.put("class","unknown");result.put("method","unknown");result.put("line",-1);}
        return result;
    }
    AssertionError capturePrimary(Throwable failure,String stage) {
        primary=describe("verification",stage,failure);
        var safe=new AssertionError("Verification failed; type="+primary.get("type")+", stage="+primary.get("stage")
                +", location="+primary.get("class")+"."+primary.get("method")+":"+primary.get("line"));
        safe.setStackTrace(Arrays.stream(failure.getStackTrace()).filter(x -> x.getClassName().startsWith("com.knode.user."))
                .toArray(StackTraceElement[]::new));
        return safe;
    }
    void cleanupFailure(String step,String stage,Throwable failure) {cleanup.add(describe(step,stage,failure));}
    Map<String,Object> primary() {return primary;}
    List<Map<String,Object>> cleanup() {return List.copyOf(cleanup);}
    int cleanupCount() {return cleanup.size();}
    void finish(AssertionError primaryFailure) {
        if (cleanup.isEmpty()) return;
        var failure=new IllegalStateException("Cleanup failed; see sanitized manifest entries");
        for (var issue:cleanup) failure.addSuppressed(new IllegalStateException("step="+issue.get("step")
                +", type="+issue.get("type")+", location="+issue.get("class")+"."+issue.get("method")+":"+issue.get("line")));
        if (primaryFailure!=null) primaryFailure.addSuppressed(failure);
        else throw failure;
    }
}
