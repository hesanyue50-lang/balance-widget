package com.minis.balancewidget;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API Key 的金库：用 Android Keystore 里的设备绑定密钥做 AES-GCM 加密。
 *
 * 为什么这个能扛 root：
 *   密钥本体生成在 **TEE / StrongBox** 里，`AndroidKeyStore` 只交出「用它加解密」的能力，
 *   **密钥字节永远不出安全硬件**。所以就算把 /data/data 整个拖走，拿到的也只是密文 ——
 *   换设备、刷机、清 Keystore 都解不开（那就当没配过，让用户重填）。
 *
 * 和「查看密码」的分工：
 *   查看密码管的是「人在你手机前面能不能看到」；
 *   Keystore 管的是「数据被拷走能不能看到」。两者互不依赖，所以后台定时刷新
 *   依然能自动解密取数，不需要你每次输密码。
 *
 * 密文格式：v1: + base64( 1字节iv长度 + iv + 密文&tag )
 * 没有 v1: 前缀的一律当老明文处理，读取时原样返回，下次保存自动转为密文。
 */
public final class KeyVault {

    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "bw_vault_v1";
    private static final String PREFIX = "v1:";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private KeyVault() { }

    public static boolean isEncrypted(String s) {
        return s != null && s.startsWith(PREFIX);
    }

    /**
     * 加密。
     *
     * <p>⚠️ 失败时**绝不返回明文**。原来这里是 {@code catch { return plain; }}，
     * 注释写"宁可暂时明文，也绝不能把用户的 Key 弄丢"—— 但代价是：
     * 密钥以明文落进 shared_prefs，**备份文件里也是明文**，
     * 而界面和文档一直宣称"加密存储、拷走也解不开"。而且事后完全无法分辨哪条是明文。
     *
     * <p>失败只说明 Keystore 这一刻不可用（开机初期 keymaster 未就绪、别名被占用），
     * 重试一次通常就好；仍失败就抛出去让上层放弃这次保存并提示用户 ——
     * 少存一次，比悄悄降级成明文好。
     */
    public static String enc(String plain) {
        if (plain == null || plain.length() == 0) return "";
        if (isEncrypted(plain)) return plain;
        Throwable last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Cipher c = Cipher.getInstance(TRANSFORM);
                c.init(Cipher.ENCRYPT_MODE, secretKey());
                byte[] iv = c.getIV();
                byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
                byte[] out = new byte[1 + iv.length + ct.length];
                out[0] = (byte) iv.length;
                System.arraycopy(iv, 0, out, 1, iv.length);
                System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
                String enc = PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
                if (!isEncrypted(enc)) throw new IllegalStateException("加密结果格式异常");
                return enc;
            } catch (Throwable t) {
                last = t;
                try { Thread.sleep(120); } catch (InterruptedException ig) { }
            }
        }
        throw new IllegalStateException("密钥加密失败，已阻止明文落盘", last);
    }

    /** 解密。兼容未加密的老数据（原样返回） */
    public static String dec(String stored) {
        if (stored == null || stored.length() == 0) return "";
        if (!isEncrypted(stored)) return stored;
        try {
            byte[] all = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            int ivLen = all[0] & 0xFF;
            byte[] iv = new byte[ivLen];
            byte[] ct = new byte[all.length - 1 - ivLen];
            System.arraycopy(all, 1, iv, 0, ivLen);
            System.arraycopy(all, 1 + ivLen, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            /* 解不开说明换了设备或 Keystore 被清空，当作没配置 */
            return "";
        }
    }

    /** 取（必要时创建）设备绑定的 AES 密钥 */
    private static synchronized SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }
}
