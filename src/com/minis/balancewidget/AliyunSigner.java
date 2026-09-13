package com.minis.balancewidget;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import android.util.Base64;

/**
 * 阿里云 API 签名工具类（V1 签名机制）
 * 用于调用阿里云 BSS OpenAPI 查询余额
 * 
 * 文档：https://help.aliyun.com/document_detail/25492.html
 */
public class AliyunSigner {
    
    /**
     * 构造阿里云 API 请求 URL（包含签名）
     * 
     * @param accessKeyId AccessKeyId
     * @param accessKeySecret AccessKeySecret
     * @param action API Action（如 QueryAccountBalance）
     * @param endpoint API 端点（如 business.aliyuncs.com）
     * @return 完整的请求 URL（包含签名）
     */
    public static String signRequest(String accessKeyId, String accessKeySecret, 
                                     String action, String endpoint) throws Exception {
        return signRequest(accessKeyId, accessKeySecret, action, endpoint, null);
    }

    /**
     * 构造阿里云 API 请求 URL（含签名，支持额外业务参数）
     * 参数按字典序排列（V1 签名要求），额外参数自动并入排序。
     */
    public static String signRequest(String accessKeyId, String accessKeySecret,
                                     String action, String endpoint,
                                     java.util.Map<String, String> extra) throws Exception {
        // 公共请求参数
        java.util.TreeMap<String, String> params = new java.util.TreeMap<String, String>();
        params.put("AccessKeyId", accessKeyId);
        params.put("Action", action);
        params.put("Format", "JSON");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", getIso8601Timestamp());
        params.put("Version", "2017-12-14");
        if (extra != null) params.putAll(extra);

        // 构造参数字符串（TreeMap 已按字母顺序排序）
        StringBuilder paramStr = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : params.entrySet()) {
            if (paramStr.length() > 0) paramStr.append('&');
            paramStr.append(e.getKey()).append('=').append(encode(e.getValue()));
        }
        
        // 构造待签名字符串
        String stringToSign = "GET&" + encode("/") + "&" + encode(paramStr.toString());
        
        // 计算签名
        String signature = calculateSignature(accessKeySecret, stringToSign);
        
        // 构造完整 URL
        return "https://" + endpoint + "/?" + paramStr.toString() + "&Signature=" + encode(signature);
    }
    
    /**
     * 获取 ISO8601 格式的时间戳（UTC）
     */
    private static String getIso8601Timestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }
    
    /**
     * URL 编码（特殊处理：空格编码为 %20，星号编码为 %2A，%7E 解码为 ~）
     */
    private static String encode(String value) throws UnsupportedEncodingException {
        if (value == null) return "";
        return URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
    
    /**
     * 计算 HMAC-SHA1 签名
     */
    private static String calculateSignature(String accessKeySecret, String stringToSign) throws Exception {
        // 签名密钥 = AccessKeySecret + "&"
        String signingKey = accessKeySecret + "&";
        
        // HMAC-SHA1
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(signingKey.getBytes("UTF-8"), "HmacSHA1"));
        byte[] signData = mac.doFinal(stringToSign.getBytes("UTF-8"));
        
        // Base64 编码
        return Base64.encodeToString(signData, Base64.NO_WRAP);
    }
    
    /**
     * 查询阿里云账户余额
     * 
     * @param accessKeyId AccessKeyId
     * @param accessKeySecret AccessKeySecret
     * @return 余额 JSON 字符串
     */
    public static String queryAccountBalance(String accessKeyId, String accessKeySecret) throws Exception {
        String url = signRequest(accessKeyId, accessKeySecret, 
                                "QueryAccountBalance", "business.aliyuncs.com");
        
        // 使用 BalanceFetcher 的 HTTP 客户端发送请求
        return BalanceFetcher.httpGet(url, null, 8000);
    }

    /**
     * 查询订阅实例列表（Token Plan 套餐的实例与到期时间）
     * 订阅制没有"账户余额"概念，余量在百炼控制台；
     * 这里能拿到的是实例状态与到期时间。
     */
    public static String queryAvailableInstances(String accessKeyId, String accessKeySecret) throws Exception {
        java.util.Map<String, String> extra = new java.util.HashMap<String, String>();
        extra.put("PageNum", "1");
        extra.put("PageSize", "20");
        String url = signRequest(accessKeyId, accessKeySecret,
                "QueryAvailableInstances", "business.aliyuncs.com", extra);
        return BalanceFetcher.httpGet(url, null, 8000);
    }
}
