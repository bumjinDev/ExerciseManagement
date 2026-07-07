package com.exercisemanagement.challenge.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

import com.exercisemanagement.challenge.config.ChallengeProperties;

/**
 * 인증샷 로컬 저장소 (변경사항 문서 2-1: 로컬 디렉토리 + 경로 보관으로 확정).
 * 저장 경로: {app.photo-dir}/{challengeId}/{submissionId}.{확장자}
 */
@Component
public class PhotoStorage {

    private final ChallengeProperties properties;

    public PhotoStorage(ChallengeProperties properties) {
        this.properties = properties;
    }

    /** 사진 바이트의 SHA-256 해시(16진 문자열). 해시 중복 검사의 기준값. */
    public String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    /** 사진 파일 저장 후 저장 경로를 반환한다. */
    public String save(String challengeId, String submissionId, byte[] bytes, String originalFilename) {
        String extension = "jpg";
        if (originalFilename != null && originalFilename.lastIndexOf('.') > -1) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.') + 1);
        }
        try {
            Path dir = Paths.get(properties.getPhotoDir(), challengeId);
            Files.createDirectories(dir);
            Path file = dir.resolve(submissionId + "." + extension);
            Files.write(file, bytes);
            return file.toString();
        } catch (IOException e) {
            throw new IllegalStateException("인증샷 저장에 실패했습니다", e);
        }
    }
}
