package org.apache.druid.data.catalog;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = org.apache.druid.data.catalog.InputCatalog.TYPE_PROPERTY)
public interface InputCatalog {
    String TYPE_PROPERTY = "type";
}
