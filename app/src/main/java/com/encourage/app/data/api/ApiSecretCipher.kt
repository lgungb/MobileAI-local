/*
 * Encourage — API 密钥加密器（M5-3 / 工程质量）
 *
 * 【设计】
 * - AndroidKeyStore 内生成 AES-256-GCM 密钥（别名 encourage_api_secret），
 *   密钥材料不出安全硬件，应用卸载即失效；
 * - 密文格式：enc1:<base64(iv)>.<base64(ciphertext)>，带前缀便于识别与版本升级；
 * - decrypt 对无前缀内容原样返回 —— 兼容历史明文数据，下次保存时自动升级为密文；
 * - 任何加解密异常都降级为原文/密文直存并记日志，绝不因加密问题阻塞用户保存配置。
 */

package com.encourage.app.data.api

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AGApiSecretCipher"
private const val KEY_ALIAS = "encourage_api_secret"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val PREFIX = "enc1:"
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128

@Singleton
class ApiSecretCipher @Inject constructor() {

  /** 获取（必要时生成）Keystore 中的密钥。 */
  private fun obtainKey(): SecretKey? =
    try {
      val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        ?: run {
          val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
          generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
              )
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .build()
          )
          generator.generateKey()
        }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to obtain Keystore key", e)
      null
    }

  /** 加密；入参为空则原样返回空串；失败时降级返回原文。 */
  fun encrypt(plainText: String): String {
    if (plainText.isEmpty()) {
      return ""
    }
    return try {
      val key = obtainKey() ?: return plainText
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val iv = cipher.iv
      val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
      PREFIX +
        Base64.encodeToString(iv, Base64.NO_WRAP) +
        "." +
        Base64.encodeToString(cipherText, Base64.NO_WRAP)
    } catch (e: Exception) {
      Log.e(TAG, "Encrypt failed, falling back to plaintext", e)
      plainText
    }
  }

  /**
   * 解密；无 [PREFIX] 前缀的输入（历史明文）原样返回，实现平滑迁移；
   * 解密失败（如密钥被清除）返回空串，让上层当作未配置处理，而不是把密文当密钥用。
   */
  fun decrypt(stored: String): String {
    if (stored.isEmpty() || !stored.startsWith(PREFIX)) {
      return stored
    }
    return try {
      val key = obtainKey() ?: return ""
      val body = stored.removePrefix(PREFIX)
      val separator = body.indexOf('.')
      if (separator <= 0) {
        return ""
      }
      val iv = Base64.decode(body.substring(0, separator), Base64.NO_WRAP)
      val cipherText = Base64.decode(body.substring(separator + 1), Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
      String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (e: Exception) {
      Log.e(TAG, "Decrypt failed", e)
      ""
    }
  }
}
