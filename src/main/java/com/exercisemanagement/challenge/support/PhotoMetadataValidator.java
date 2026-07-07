package com.exercisemanagement.challenge.support;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.exercisemanagement.challenge.exception.ChallengeApiException;
import com.exercisemanagement.challenge.exception.ErrorCode;

/**
 * 사진 메타데이터 정합성 검사 (명세 8.2.1 항목 3).
 * 기준 구현 규칙(변경사항 문서 4-4):
 *   1) 파일이 파싱 가능한 이미지가 아니면 거부
 *   2) EXIF 촬영 시각(DateTimeOriginal)이 있으면 수행 날짜와 ±1일 정합이어야 함
 *      (EXIF가 없는 사진은 통과 — 스크린샷·메신저 전송본 고려)
 */
@Component
public class PhotoMetadataValidator {

    public void validate(byte[] photoBytes, LocalDate linkedDate) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(photoBytes));
        } catch (Exception e) {
            throw new ChallengeApiException(ErrorCode.E_SUB_META_MISMATCH, "이미지로 인식할 수 없는 파일입니다.");
        }

        ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        if (exif == null) {
            return;
        }
        Date taken = exif.getDateOriginal();
        if (taken == null) {
            return;
        }

        LocalDate takenDate = taken.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        long dayGap = Math.abs(takenDate.toEpochDay() - linkedDate.toEpochDay());
        if (dayGap > 1) {
            throw new ChallengeApiException(ErrorCode.E_SUB_META_MISMATCH,
                    "사진 촬영 시각(" + takenDate + ")이 수행 날짜(" + linkedDate + ")와 정합하지 않습니다.");
        }
    }
}
