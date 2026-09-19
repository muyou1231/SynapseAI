package com.synapseai.dto;

public class LoginRequest {

    /** 登录使用 10 位账号（而非用户名） */
    private String account;
    private String password;

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
