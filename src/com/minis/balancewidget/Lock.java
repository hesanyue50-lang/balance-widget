package com.minis.balancewidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 访问锁：密码 + 密保问题。
 *
 * 只存 PBKDF2 派生的哈希和随机盐，**不存明文密码，也不存明文答案**。
 * 忘了密码可以用自设的密保问题重置。
 *
 * 定位说明：这套东西防的是「别人拿到手机、随手点开就能看到你的 Key」，
 * 不是「设备被 root 后提取数据」。因为 API Key 必须能被后台自动取用来刷新余额，
 * 它没法用你的密码加密 —— 否则每次后台刷新都得先输一遍密码。
 */
public final class Lock {

    private static final String K_HASH = "lock_hash";
    private static final String K_SALT = "lock_salt";
    private static final String K_QUESTION = "lock_question";
    private static final String K_ANS = "lock_ans";
    private static final String K_ANS_SALT = "lock_ans_salt";

    private static final int ROUNDS = 12000;
    private static final int KEY_LEN = 256;

    private Lock() { }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(BalanceFetcher.PREFS, Context.MODE_PRIVATE);
    }

    /** 是否已经设过密码 */
    public static boolean isSet(Context c) {
        return sp(c).getString(K_HASH, "").length() > 0;
    }

    /** 是否设过密保 */
    public static boolean hasQuestion(Context c) {
        return sp(c).getString(K_QUESTION, "").length() > 0
            && sp(c).getString(K_ANS, "").length() > 0;
    }

    public static String question(Context c) {
        return sp(c).getString(K_QUESTION, "");
    }

    public static void setPassword(Context c, String pwd) {
        String salt = newSalt();
        sp(c).edit()
             .putString(K_SALT, salt)
             .putString(K_HASH, hash(pwd, salt))
             .apply();
    }

    public static void setQuestion(Context c, String q, String a) {
        String salt = newSalt();
        sp(c).edit()
             .putString(K_QUESTION, q == null ? "" : q)
             .putString(K_ANS_SALT, salt)
             .putString(K_ANS, a == null ? "" : hash(norm(a), salt))
             .apply();
    }

    public static boolean check(Context c, String pwd) {
        SharedPreferences p = sp(c);
        String salt = p.getString(K_SALT, "");
        String h = p.getString(K_HASH, "");
        if (salt.length() == 0 || h.length() == 0) return false;
        return h.equals(hash(pwd == null ? "" : pwd, salt));
    }

    public static boolean checkAnswer(Context c, String ans) {
        SharedPreferences p = sp(c);
        String salt = p.getString(K_ANS_SALT, "");
        String h = p.getString(K_ANS, "");
        if (salt.length() == 0 || h.length() == 0) return false;
        return h.equals(hash(norm(ans), salt));
    }

    /** 抹掉你存的 API Key（重置密码之外的用途，慎用） */
    public static void clearPassword(Context c) {
        sp(c).edit().remove(K_HASH).remove(K_SALT).apply();
    }

    /** 答案忽略大小写和首尾空白 —— 找回时没人能一字不差复现当初怎么打的 */
    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String hash(String s, String salt) {
        try {
            KeySpec spec = new PBEKeySpec(s.toCharArray(), salt.getBytes("UTF-8"), ROUNDS, KEY_LEN);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return Base64.encodeToString(f.generateSecret(spec).getEncoded(), Base64.NO_WRAP);
        } catch (Throwable t) {
            /* 极端情况退化到 SHA-256，至少不比明文差 */
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                md.update(salt.getBytes("UTF-8"));
                return Base64.encodeToString(md.digest(s.getBytes("UTF-8")), Base64.NO_WRAP);
            } catch (Throwable t2) {
                return "";
            }
        }
    }
}
