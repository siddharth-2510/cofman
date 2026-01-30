package com.salescode.cofman.model;

import com.salescode.cofman.model.dto.DomainConfig;
import com.salescode.cofman.util.JSONArrayConverter;
import com.vladmihalcea.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Merge {

    @Id
    private String id;

    private String fromBranch;
    private String toBranch;
    private String requester;
    private String merger;
    private boolean approved;
    private boolean merged;
    private String repoUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<DomainConfig> domainConfigs = new ArrayList<>();

    private String response;
}
