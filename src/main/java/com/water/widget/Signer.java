package com.water.widget;

import java.security.MessageDigest;

/**
 * 签名工具类。
 *
 * 开源仓库中仅提供占位实现。本地构建时需在 secrets.properties 中配置 SIGN_SALT，
 * 构建系统会将其注入 BuildConfig，本类再通过 IlifeApi.sign() 间接使用。
 *
 * 如需完整的签名功能，请自行实现签名逻辑并配置必要的参数。
 */
public class Signer {

    /**
     * 生成签名。
     * 当 salt 为空时返回空字符串，表示签名功能未配置。
     * 本地开发者请参考项目文档实现签名算法。
     */
    static String sign(String adId, String token, String uid, String salt) {
        if (salt == null || salt.isEmpty()) {
            return "";
        }
        try {
            long now = System.currentTimeMillis();
            long n = 10 * (now / 10000);
            String e = token.length() >= 8 ? token.substring(token.length() - 8) : token;
            String t = uid.length() >= 8 ? uid.substring(uid.length() - 8) : uid;
            String raw = adId + n + e + t + salt;
            return md5(raw);
        } catch (Exception ex) {
            return "";
        }
    }

    /** 小程序 2.0.178：30 秒时间桶；与旧 App 签名分开维护。 */
    static String signMini(String adId, String token, String uid, String salt, long nowMillis) {
        if (salt == null || salt.isEmpty()) {
            throw new IllegalStateException("MINI_SIGN_SALT 未配置");
        }
        long bucket = 30 * (nowMillis / 30000);
        String tokenTail = token.substring(Math.max(0, token.length() - 8));
        String uidTail = uid.substring(Math.max(0, uid.length() - 8));
        return md5(adId + bucket + tokenTail + uidTail + salt);
    }

    /** App 3.1.9：独立盐值，传入经服务端时间校准后的毫秒数。 */
    static String signApp(String adId, String token, String uid, String salt, long serverTimeMillis) {
        if (salt == null || salt.isEmpty()) {
            throw new IllegalStateException("APP_SIGN_SALT 未配置");
        }
        long bucket = 30 * (serverTimeMillis / 30000);
        String tokenTail = token.substring(Math.max(0, token.length() - 8));
        String uidTail = uid.substring(Math.max(0, uid.length() - 8));
        return md5(adId + bucket + tokenTail + uidTail + salt);
    }

    /** App 短信登录：无时间桶和账号尾部，只签本次手机号与验证码。 */
    static String signAppLogin(String phone, String smsCode, String salt) {
        if (salt == null || salt.isEmpty()) {
            throw new IllegalStateException("APP_SIGN_SALT 未配置");
        }
        return md5("authCode=" + smsCode + "&un=" + phone + salt);
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
