package com.exercisemanagement.members.model;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class MemberConverter {

    /**
     * MemberDTO -> MembersEntity 변환
     */
    public MembersEntity toEntity(MemberDTO dto) {
        if (dto == null) {
            return null;
        }

        return MembersEntity.builder()
                .id(dto.getId())
                .pw(dto.getPw())
                .nickName(dto.getNickName())
                .tel(dto.getTel())
                .email(dto.getEmail())
                .joinDate(dto.getJoinDate())
                .build();
    }

    /**
     * MembersEntity -> MemberDTO 변환
     */
    public MemberDTO toDTO(MembersEntity entity) {
        if (entity == null) {
            return null;
        }

        return new MemberDTO(
                entity.getId(),
                entity.getPw(),
                entity.getNickName(),
                entity.getTel(),
                entity.getEmail(),
                entity.getJoinDate()
        );
    }

    /**
     * List<MemberDTO> -> List<MembersEntity> 변환
     */
    public List<MembersEntity> toEntityList(List<MemberDTO> dtoList) {
        if (dtoList == null || dtoList.isEmpty()) {
            return List.of();
        }
        return dtoList.stream().map(this::toEntity).collect(Collectors.toList());
    }

    /**
     * List<MembersEntity> -> List<MemberDTO> 변환
     */
    public List<MemberDTO> toDTOList(List<MembersEntity> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return List.of();
        }
        return entityList.stream().map(this::toDTO).collect(Collectors.toList());
    }
}
