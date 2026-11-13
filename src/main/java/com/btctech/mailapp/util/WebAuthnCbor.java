package com.btctech.mailapp.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/**
 * Lightweight, zero-dependency CBOR decoder and WebAuthn AuthData / COSE Public Key parser.
 * Conforms to RFC 8949 (CBOR) and W3C WebAuthn Level 2 specifications.
 */
@Slf4j
public class WebAuthnCbor {

    @Data
    public static class ParsedAuthData {
        private byte[] rpIdHash;
        private byte flags;
        private long signCount;
        private byte[] aaguid;
        private byte[] credentialId;
        private PublicKey publicKey;
        private int algorithm = -7; // Default ES256 (-7)

        public boolean isUserPresent() {
            return (flags & 0x01) != 0;
        }

        public boolean isUserVerified() {
            return (flags & 0x04) != 0;
        }

        public boolean hasAttestedCredentialData() {
            return (flags & 0x40) != 0;
        }
    }

    /**
     * Parses WebAuthn authenticatorData bytes (from registration attestationObject or login authenticatorData)
     */
    public static ParsedAuthData parseAuthData(byte[] authData) {
        if (authData == null || authData.length < 37) {
            throw new IllegalArgumentException("Invalid authData length: minimum 37 bytes required");
        }

        ParsedAuthData result = new ParsedAuthData();

        // 1. rpIdHash (bytes 0..31)
        byte[] rpIdHash = new byte[32];
        System.arraycopy(authData, 0, rpIdHash, 0, 32);
        result.setRpIdHash(rpIdHash);

        // 2. flags (byte 32)
        byte flags = authData[32];
        result.setFlags(flags);

        // 3. signCount (bytes 33..36 as 32-bit unsigned big-endian int)
        long signCount = ((authData[33] & 0xFFL) << 24)
                | ((authData[34] & 0xFFL) << 16)
                | ((authData[35] & 0xFFL) << 8)
                | (authData[36] & 0xFFL);
        result.setSignCount(signCount);

        // If Attested Credential Data is present (Registration)
        if (result.hasAttestedCredentialData()) {
            if (authData.length < 55) {
                throw new IllegalArgumentException("authData contains AT flag but is too short: " + authData.length);
            }

            // 4. aaguid (bytes 37..52)
            byte[] aaguid = new byte[16];
            System.arraycopy(authData, 37, aaguid, 0, 16);
            result.setAaguid(aaguid);

            // 5. credentialIdLength (bytes 53..54)
            int credIdLen = ((authData[53] & 0xFF) << 8) | (authData[54] & 0xFF);
            if (authData.length < 55 + credIdLen) {
                throw new IllegalArgumentException("Invalid credentialId length in authData: " + credIdLen);
            }

            // 6. credentialId (bytes 55 .. 55 + credIdLen - 1)
            byte[] credId = new byte[credIdLen];
            System.arraycopy(authData, 55, credId, 0, credIdLen);
            result.setCredentialId(credId);

            // 7. COSE Key (bytes 55 + credIdLen to end)
            int coseOffset = 55 + credIdLen;
            int coseLen = authData.length - coseOffset;
            byte[] coseBytes = new byte[coseLen];
            System.arraycopy(authData, coseOffset, coseBytes, 0, coseLen);

            CborDecoder decoder = new CborDecoder(coseBytes);
            Object coseDecoded = decoder.decode();
            if (coseDecoded instanceof Map<?, ?> coseMap) {
                PublicKey pubKey = coseToPublicKey(coseMap);
                result.setPublicKey(pubKey);

                Number algNum = (Number) coseMap.get(3L);
                if (algNum == null) {
                    algNum = (Number) coseMap.get(3);
                }
                if (algNum != null) {
                    result.setAlgorithm(algNum.intValue());
                }
            }
        }

        return result;
    }

    /**
     * Converts a COSE key map into a standard Java PublicKey (Supports ES256 and RS256)
     */
    public static PublicKey coseToPublicKey(Map<?, ?> coseMap) {
        Number ktyNum = getNumberFromMap(coseMap, 1);
        Number algNum = getNumberFromMap(coseMap, 3);

        int kty = ktyNum != null ? ktyNum.intValue() : 2; // 2 = EC2, 3 = RSA
        int alg = algNum != null ? algNum.intValue() : -7; // -7 = ES256, -257 = RS256

        try {
            if (kty == 2) { // EC2 (P-256)
                byte[] x = getBytesFromMap(coseMap, -2);
                byte[] y = getBytesFromMap(coseMap, -3);

                if (x == null || y == null) {
                    throw new IllegalArgumentException("Missing EC2 x or y coordinates in COSE map");
                }

                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec ecParameterSpec = parameters.getParameterSpec(ECParameterSpec.class);

                ECPoint ecPoint = new ECPoint(new BigInteger(1, x), new BigInteger(1, y));
                ECPublicKeySpec keySpec = new ECPublicKeySpec(ecPoint, ecParameterSpec);
                return KeyFactory.getInstance("EC").generatePublic(keySpec);

            } else if (kty == 3) { // RSA
                byte[] n = getBytesFromMap(coseMap, -1);
                byte[] e = getBytesFromMap(coseMap, -2);

                if (n == null || e == null) {
                    throw new IllegalArgumentException("Missing RSA modulus or exponent in COSE map");
                }

                RSAPublicKeySpec rsaSpec = new RSAPublicKeySpec(new BigInteger(1, n), new BigInteger(1, e));
                return KeyFactory.getInstance("RSA").generatePublic(rsaSpec);

            } else {
                throw new UnsupportedOperationException("Unsupported COSE key type: " + kty + " (alg: " + alg + ")");
            }
        } catch (Exception e) {
            log.error("Failed to construct PublicKey from COSE map: {}", e.getMessage());
            throw new RuntimeException("Could not parse WebAuthn public key", e);
        }
    }

    /**
     * Verifies WebAuthn digital signature:
     * Signed data is: authenticatorData || SHA-256(clientDataJSON)
     */
    public static boolean verifySignature(PublicKey publicKey, int algorithm, byte[] authData, byte[] clientDataJSON, byte[] signature) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] clientDataHash = sha256.digest(clientDataJSON);

            byte[] signedData = new byte[authData.length + clientDataHash.length];
            System.arraycopy(authData, 0, signedData, 0, authData.length);
            System.arraycopy(clientDataHash, 0, signedData, authData.length, clientDataHash.length);

            String sigAlg = (algorithm == -257) ? "SHA256withRSA" : "SHA256withECDSA";
            Signature sig = Signature.getInstance(sigAlg);
            sig.initVerify(publicKey);
            sig.update(signedData);

            return sig.verify(signature);
        } catch (Exception e) {
            log.error("WebAuthn signature verification failed with error: {}", e.getMessage());
            return false;
        }
    }

    public static String encodePublicKeyToX509Base64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey decodePublicKeyFromX509Base64(String base64, int algorithm) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            String keyType = (algorithm == -257) ? "RSA" : "EC";
            return KeyFactory.getInstance(keyType).generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Could not decode stored public key", e);
        }
    }

    private static Number getNumberFromMap(Map<?, ?> map, long key) {
        Object val = map.get(key);
        if (val == null) {
            val = map.get((int) key);
        }
        if (val instanceof Number n) {
            return n;
        }
        return null;
    }

    private static byte[] getBytesFromMap(Map<?, ?> map, long key) {
        Object val = map.get(key);
        if (val == null) {
            val = map.get((int) key);
        }
        if (val instanceof byte[] b) {
            return b;
        }
        return null;
    }

    /**
     * Standard Recursive CBOR Parser (RFC 8949)
     */
    public static class CborDecoder {
        private final ByteBuffer buffer;

        public CborDecoder(byte[] bytes) {
            this.buffer = ByteBuffer.wrap(bytes);
        }

        public Object decode() {
            if (!buffer.hasRemaining()) {
                return null;
            }
            int initial = buffer.get() & 0xFF;
            int majorType = initial >> 5;
            int info = initial & 0x1F;
            long length = readLength(info);

            switch (majorType) {
                case 0: // unsigned integer
                    return length;
                case 1: // negative integer
                    return -1L - length;
                case 2: // byte string
                    byte[] bytes = new byte[(int) length];
                    buffer.get(bytes);
                    return bytes;
                case 3: // text string (UTF-8)
                    byte[] textBytes = new byte[(int) length];
                    buffer.get(textBytes);
                    return new String(textBytes, StandardCharsets.UTF_8);
                case 4: // array
                    List<Object> list = new ArrayList<>((int) length);
                    for (int i = 0; i < length; i++) {
                        list.add(decode());
                    }
                    return list;
                case 5: // map
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < length; i++) {
                        Object k = decode();
                        Object v = decode();
                        map.put(k, v);
                    }
                    return map;
                case 7: // simple / bool
                    if (info == 20) return Boolean.FALSE;
                    if (info == 21) return Boolean.TRUE;
                    if (info == 22) return null;
                    return null;
                default:
                    // Skip unsupported major types
                    return null;
            }
        }

        private long readLength(int info) {
            if (info < 24) {
                return info;
            } else if (info == 24) {
                return buffer.get() & 0xFFL;
            } else if (info == 25) {
                return buffer.getShort() & 0xFFFFL;
            } else if (info == 26) {
                return buffer.getInt() & 0xFFFFFFFFL;
            } else if (info == 27) {
                return buffer.getLong();
            }
            throw new IllegalArgumentException("Reserved or indefinite CBOR additional info: " + info);
        }
    }
}

