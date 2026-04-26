package org.example;

enum ReturnCode {
    SUCCESS(0,"ok"),
    ADDRESS_NULL(1, "地址为空"),
    LOW_BALANCE(2, "余额不足"),
    SIGN_ERROR(3, "签名验签失败"),
    ACCOUNT_NOT_FOUND(4, "账户不存在"),
    NONCE_INVALID(5, "nonce错误"),
    SYSTEM_ERROR(99, "系统错误");

    public int code;
    private String msg;

    private ReturnCode(int code , String msg){
        this.code = code;
        this.msg = msg;
    }

    public String getMsg(){
        return this.msg;
    }

    @Override
    public String toString(){
        return "Code " + this.code + ":" + this.msg;
    }
}
