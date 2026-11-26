package com.knode.search.model;

public enum SearchErrorCode {
    SUCCESS(200, "操作成功"),
    PARAM_ERROR(515, "参数不合法"),
    SEARCH_SERVICE_UNAVAILABLE(517, "搜索服务暂不可用");

    private final int code;
    private final String msg;

    SearchErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
