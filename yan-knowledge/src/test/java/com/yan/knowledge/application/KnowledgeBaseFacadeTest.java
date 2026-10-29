package com.yan.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yan.knowledge.domain.entity.KnowledgeBaseDocumentEntity;
import com.yan.knowledge.domain.entity.KnowledgeBaseEntity;
import com.yan.knowledge.mapper.KnowledgeBaseDocumentMapper;
import com.yan.knowledge.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseFacadeTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeBaseDocumentMapper baseDocumentMapper;

    private KnowledgeBaseFacade facade;

    @BeforeEach
    void setUp() {
        facade = new KnowledgeBaseFacade(knowledgeBaseMapper, baseDocumentMapper);
    }

    @Test
    void createsPrivateKnowledgeBaseForOwner() {
        doAnswer(invocation -> {
            KnowledgeBaseEntity base = invocation.getArgument(0);
            base.setId(9L);
            return 1;
        }).when(knowledgeBaseMapper).insert(any(KnowledgeBaseEntity.class));

        KnowledgeBaseEntity base = facade.createPrivate(7L, " Java ", " notes ");

        assertThat(base.getId()).isEqualTo(9L);
        assertThat(base.getType()).isEqualTo("PRIVATE");
        assertThat(base.getOwnerId()).isEqualTo(7L);
        assertThat(base.getName()).isEqualTo("Java");
        assertThat(base.getDescription()).isEqualTo("notes");
    }

    @Test
    void allowsAuthenticatedUsersToReadSystemKnowledgeBase() {
        KnowledgeBaseEntity base = facade.requireReadable(7L, 1L);

        assertThat(base.getId()).isEqualTo(1L);
        assertThat(base.getType()).isEqualTo("SYSTEM");
        verifyNoInteractions(knowledgeBaseMapper);
    }

    @Test
    void allowsOwnerToReadPrivateKnowledgeBase() {
        KnowledgeBaseEntity base = new KnowledgeBaseEntity();
        base.setId(9L);
        base.setType("PRIVATE");
        base.setOwnerId(7L);
        when(knowledgeBaseMapper.selectById(9L)).thenReturn(base);

        assertThat(facade.requireReadable(7L, 9L)).isSameAs(base);
    }

    @Test
    void rejectsUpdateWhenKnowledgeBaseDoesNotBelongToOwner() {
        KnowledgeBaseEntity base = new KnowledgeBaseEntity();
        base.setId(9L);
        base.setType("PRIVATE");
        base.setOwnerId(8L);
        when(knowledgeBaseMapper.selectById(9L)).thenReturn(base);

        assertThatThrownBy(() -> facade.updatePrivate(7L, 9L, "Java", null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(knowledgeBaseMapper, never()).update(any(), any());
    }

    @Test
    void removesOnlyAssociationWhenDeletingPrivateKnowledgeBase() {
        KnowledgeBaseEntity base = new KnowledgeBaseEntity();
        base.setId(9L);
        base.setType("PRIVATE");
        base.setOwnerId(7L);
        when(knowledgeBaseMapper.selectById(9L)).thenReturn(base);
        when(knowledgeBaseMapper.deleteById(9L)).thenReturn(1);

        facade.deletePrivate(7L, 9L);

        verify(baseDocumentMapper).delete(any());
        verify(knowledgeBaseMapper).deleteById(9L);
    }
}
