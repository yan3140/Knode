package com.knode.community.follow;

public final class CommunityBusinessFailure extends RuntimeException {
    private final int httpStatus;
    private final int businessCode;
    private final String publicMessage;

    public CommunityBusinessFailure(int httpStatus, int businessCode, String publicMessage) {
        super(publicMessage);
        this.httpStatus = httpStatus;
        this.businessCode = businessCode;
        this.publicMessage = publicMessage;
    }

    public static CommunityBusinessFailure paramError() {
        return new CommunityBusinessFailure(200, 515, "\u53c2\u6570\u4e0d\u5408\u6cd5");
    }

    public static CommunityBusinessFailure userUnavailable() {
        return new CommunityBusinessFailure(503, 500, "\u7528\u6237\u670d\u52a1\u6682\u4e0d\u53ef\u7528");
    }

    public static CommunityBusinessFailure communityUnavailable() {
        return new CommunityBusinessFailure(503, 500, "\u793e\u533a\u670d\u52a1\u6682\u4e0d\u53ef\u7528");
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int businessCode() {
        return businessCode;
    }

    public String publicMessage() {
        return publicMessage;
    }
}
