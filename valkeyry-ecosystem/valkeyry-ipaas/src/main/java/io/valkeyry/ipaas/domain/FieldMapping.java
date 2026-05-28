package io.valkeyry.ipaas.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Table("field_mappings")
public class FieldMapping {
    @Id private UUID id;
    @Column("consumer_id") private UUID consumerId;
    @Column("target_field") private String targetField;
    @Column("source_path")  private String sourcePath;
}
