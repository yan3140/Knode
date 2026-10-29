package com.yan.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yan.knowledge.domain.KnowledgeBaseType;
import com.yan.knowledge.domain.KnowledgeBaseIds;
import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeBaseEntity;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeBaseMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseFacade {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseDocumentMapper baseDocumentMapper;

    public KnowledgeBaseFacade(KnowledgeBaseMapper knowledgeBaseMapper,
                               KnowledgeBaseDocumentMapper baseDocumentMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.baseDocumentMapper = baseDocumentMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity createPrivate(Long ownerId, String name, String description) {
        requireOwner(ownerId);
        KnowledgeBaseEntity base = new KnowledgeBaseEntity();
        base.setType(KnowledgeBaseType.PRIVATE.name());
        base.setOwnerId(ownerId);
        base.setName(requireName(name));
        base.setDescription(normalizeDescription(description));
        if (knowledgeBaseMapper.insert(base) != 1 || base.getId() == null) {
            throw new IllegalStateException("Unable to create private knowledge base");
        }
        return base;
    }

    public List<KnowledgeBaseEntity> listPrivate(Long ownerId) {
        requireOwner(ownerId);
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getOwnerId, ownerId)
                .eq(KnowledgeBaseEntity::getType, KnowledgeBaseType.PRIVATE.name())
                .orderByDesc(KnowledgeBaseEntity::getId));
    }

    public KnowledgeBaseEntity requireReadable(Long ownerId, Long knowledgeBaseId) {
        requireOwner(ownerId);
        if (knowledgeBaseId == null || knowledgeBaseId < 1) {
            throw new IllegalArgumentException("Knowledge base id must be positive");
        }
        if (knowledgeBaseId.equals(KnowledgeBaseIds.SYSTEM)) {
            KnowledgeBaseEntity system = new KnowledgeBaseEntity();
            system.setId(KnowledgeBaseIds.SYSTEM);
            system.setType(KnowledgeBaseType.SYSTEM.name());
            system.setName("系统知识库");
            return system;
        }
        return ownedPrivate(ownerId, knowledgeBaseId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updatePrivate(Long ownerId, Long knowledgeBaseId, String name, String description) {
        ownedPrivate(ownerId, knowledgeBaseId);
        int updated = knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBaseEntity>()
                .eq(KnowledgeBaseEntity::getId, knowledgeBaseId)
                .set(KnowledgeBaseEntity::getName, requireName(name))
                .set(KnowledgeBaseEntity::getDescription, normalizeDescription(description)));
        if (updated != 1) {
            throw new IllegalStateException("Unable to update private knowledge base");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePrivate(Long ownerId, Long knowledgeBaseId) {
        ownedPrivate(ownerId, knowledgeBaseId);
        baseDocumentMapper.delete(new LambdaQueryWrapper<KnowledgeBaseDocumentEntity>()
                .eq(KnowledgeBaseDocumentEntity::getKnowledgeBaseId, knowledgeBaseId));
        if (knowledgeBaseMapper.deleteById(knowledgeBaseId) != 1) {
            throw new IllegalStateException("Unable to delete private knowledge base");
        }
    }

    KnowledgeBaseEntity ownedPrivate(Long ownerId, Long knowledgeBaseId) {
        requireOwner(ownerId);
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("Knowledge base id must not be null");
        }
        KnowledgeBaseEntity base = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (base == null || !KnowledgeBaseType.PRIVATE.name().equals(base.getType())
                || !ownerId.equals(base.getOwnerId())) {
            throw new IllegalArgumentException("Knowledge base is not owned by the current user");
        }
        return base;
    }

    private void requireOwner(Long ownerId) {
        if (ownerId == null || ownerId < 1) {
            throw new IllegalArgumentException("Owner id must be positive");
        }
    }

    private String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 128) {
            throw new IllegalArgumentException("Knowledge base name must contain 1 to 128 characters");
        }
        return name.strip();
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalized = description.strip();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("Knowledge base description must not exceed 500 characters");
        }
        return normalized;
    }
}
