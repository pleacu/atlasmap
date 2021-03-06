/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atlasmap.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.atlasmap.api.AtlasConversionException;
import io.atlasmap.api.AtlasException;
import io.atlasmap.api.AtlasPreviewContext;
import io.atlasmap.spi.AtlasInternalSession;
import io.atlasmap.spi.AtlasModule;
import io.atlasmap.spi.FieldDirection;
import io.atlasmap.v2.AtlasMapping;
import io.atlasmap.v2.AuditStatus;
import io.atlasmap.v2.Audits;
import io.atlasmap.v2.Field;
import io.atlasmap.v2.FieldGroup;
import io.atlasmap.v2.Mapping;
import io.atlasmap.v2.MappingType;

/**
 * Limited version of AtlasMap context dedicated for preview processing.
 * Since preview exchanges field values via {@code Field} object, It doesn't interact with
 * actual {@code AtlasModule} which handles data format specific work, but read the values
 * from {@code Field} object in the mapping directly.
 */
class DefaultAtlasPreviewContext extends DefaultAtlasContext implements AtlasPreviewContext {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAtlasPreviewContext.class);

    private Mapping mapping;
    private PreviewModule previewModule = new PreviewModule();


    DefaultAtlasPreviewContext(DefaultAtlasContextFactory factory) {
        super(factory, new AtlasMapping());
    }

    /**
     * Process single mapping entry in preview mode. Since modules don't participate
     * in preview mode, any document format specific function won't be applied.
     *
     * @param mapping A @link{Mapping} entry to process
     */
    @Override
    public Audits processPreview(Mapping mapping) throws AtlasException {
        DefaultAtlasSession session = new DefaultAtlasSession(this);
        this.mapping = mapping;
        session.head().setMapping(mapping);
        MappingType mappingType = mapping.getMappingType();
        String expression = mapping.getExpression();
        FieldGroup sourceFieldGroup = mapping.getInputFieldGroup();
        List<Field> sourceFields = mapping.getInputField();
        List<Field> targetFields = mapping.getOutputField();

        targetFields.forEach(tf -> tf.setValue(null));
        if ((sourceFieldGroup == null && sourceFields.isEmpty()) || targetFields.isEmpty()) {
            return session.getAudits();
        }
        if (sourceFieldGroup != null) {
            sourceFields = sourceFieldGroup.getField();
        }
        for (Field sf : sourceFields) {
            if (sf.getFieldType() == null || sf.getValue() == null) {
                continue;
            }
            if (sf.getValue() instanceof String && ((String)sf.getValue()).isEmpty()) {
                continue;
            }
            if (!restoreSourceFieldType(session, sf)) {
                return session.getAudits();
            }
        }

        processSourceFieldMapping(session);
        if (session.hasErrors()) {
            return session.getAudits();
        }

        Field sourceField = session.head().getSourceField();
        Field targetField;
        
        if (mappingType == null || mappingType == MappingType.MAP) {
            sourceFieldGroup = sourceField instanceof FieldGroup ? (FieldGroup) sourceField : null;
            for (Field f : targetFields) {
                targetField = f;
                session.head().setTargetField(targetField);
                if (sourceFieldGroup != null) {
                    if (sourceFieldGroup.getField().size() == 0) {
                        AtlasUtil.addAudit(session, targetField.getDocId(), String.format(
                                "The group field '%s:%s' Empty group field is detected, skipping",
                                sourceField.getDocId(), sourceField.getPath()),
                                targetField.getPath(), AuditStatus.WARN, null);
                        continue;
                    }
                    Integer index = targetField.getIndex();
                    AtlasPath targetPath = new AtlasPath(targetField.getPath());
                    if (targetPath.hasCollection() && !targetPath.isIndexedCollection()) {
                        if (targetFields.size() > 1) {
                            AtlasUtil.addAudit(session, targetField.getDocId(),
                                    "It's not yet supported to have a collection field as a part of multiple target fields in a same mapping",
                                    targetField.getPath(), AuditStatus.ERROR, null);
                            return session.getAudits();
                        }
                        session.head().setSourceField(sourceFieldGroup);
                    } else if (index == null) {
                        session.head().setSourceField(sourceFieldGroup.getField().get(sourceFieldGroup.getField().size()-1));
                    } else {
                        if (sourceFieldGroup.getField().size() > index) {
                            session.head().setSourceField(sourceFieldGroup.getField().get(index));
                        } else {
                            AtlasUtil.addAudit(session, targetField.getDocId(), String.format(
                                    "The number of source fields '%s' is fewer than expected via target field index '%s'",
                                    sourceFieldGroup.getField().size(), targetField.getIndex()),
                                    targetField.getPath(), AuditStatus.WARN, null);
                            continue;
                        }
                    }
                }
                if (session.hasErrors()) {
                    return session.getAudits();
                }
                if (!convertSourceToTarget(session, session.head().getSourceField(), targetField)) {
                    return session.getAudits();
                }
                Field processed = targetField;
                if (expression == null || expression.isEmpty()) {
                    processed = applyFieldActions(session, targetField);
                }
                // TODO handle collection values - https://github.com/atlasmap/atlasmap/issues/531
                targetField.setValue(processed.getValue());
            }

        } else if (mappingType == MappingType.COMBINE) {
            targetField = targetFields.get(0);
            Field combined = processCombineField(session, mapping, sourceFields, targetField);
            if (!convertSourceToTarget(session, combined, targetField)) {
                return session.getAudits();
            }
            applyFieldActions(session, targetField);

        } else if (mappingType == MappingType.SEPARATE) {
            List<Field> separatedFields;
            try {
                separatedFields = processSeparateField(session, mapping, sourceField);
            } catch (AtlasException e) {
                AtlasUtil.addAudit(session, sourceField.getDocId(), String.format(
                        "Failed to separate field: %s", AtlasUtil.getChainedMessage(e)),
                        sourceField.getPath(), AuditStatus.ERROR, null);
                if (LOG.isDebugEnabled()) {
                    LOG.error("", e);
                }
                return session.getAudits();
            }
            if (separatedFields == null) {
                return session.getAudits();
            }
            for (Field f : targetFields) {
                targetField = f;
                if (targetField.getIndex() == null || targetField.getIndex() < 0) {
                    AtlasUtil.addAudit(session, targetField.getDocId(), String.format(
                            "Separate requires zero or positive Index value to be set on targetField targetField.path=%s",
                            targetField.getPath()), targetField.getPath(), AuditStatus.WARN, null);
                    continue;
                }
                if (separatedFields.size() <= targetField.getIndex()) {
                    String errorMessage = String.format(
                            "Separate returned fewer segments count=%s when targetField.path=%s requested index=%s",
                            separatedFields.size(), targetField.getPath(), targetField.getIndex());
                    AtlasUtil.addAudit(session, targetField.getDocId(), errorMessage, targetField.getPath(),
                            AuditStatus.WARN, null);
                    break;
                }
                if (!convertSourceToTarget(session, separatedFields.get(targetField.getIndex()), targetField)) {
                    break;
                }
                applyFieldActions(session, targetField);
            }

        } else {
            AtlasUtil.addAudit(session, null, String.format(
                    "Unsupported mappingType=%s detected", mapping.getMappingType()),
                    null, AuditStatus.ERROR, null);
        }
        return session.getAudits();
    }

    private boolean restoreSourceFieldType(DefaultAtlasSession session, Field sourceField) throws AtlasException {
        try {
            Object sourceValue = getContextFactory().getConversionService().convertType(
                    sourceField.getValue(), null, sourceField.getFieldType(), null);
            sourceField.setValue(sourceValue);
        } catch (AtlasConversionException e) {
            AtlasUtil.addAudit(session, sourceField.getDocId(), String.format(
                    "Wrong format for source value : %s", AtlasUtil.getChainedMessage(e)),
                    sourceField.getPath(), AuditStatus.ERROR, null);
            if (LOG.isDebugEnabled()) {
                LOG.error("", e);
            }
            return false;
        }
        return true;
    }

    private boolean convertSourceToTarget(DefaultAtlasSession session, Field sourceField, Field targetField)
            throws AtlasException {
        Object targetValue = null;
        if (sourceField.getFieldType() != null && sourceField.getFieldType().equals(targetField.getFieldType())) {
            targetValue = sourceField.getValue();
        } else if (sourceField.getValue() != null) {
            try {
                targetValue = getContextFactory().getConversionService().convertType(sourceField.getValue(), sourceField.getFormat(),
                        targetField.getFieldType(), targetField.getFormat());
            } catch (AtlasConversionException e) {
                AtlasUtil.addAudit(session, targetField.getDocId(), String.format(
                        "Failed to convert source value to target type: %s", AtlasUtil.getChainedMessage(e)),
                        targetField.getPath(), AuditStatus.ERROR, null);
                if (LOG.isDebugEnabled()) {
                    LOG.error("", e);
                }
                return false;
            }
        }
        targetField.setValue(targetValue);
        return true;
    }

    private class PreviewModule extends BaseAtlasModule {

        @Override
        public void readSourceValue(AtlasInternalSession session) throws AtlasException {
            Field sourceField = session.head().getSourceField();
            String docId = sourceField.getDocId();
            String path = sourceField.getPath();
            FieldGroup sourceFieldGroup = mapping.getInputFieldGroup();
            if (sourceFieldGroup != null) {
                 Field f = readFromGroup(sourceFieldGroup, docId, path);
                 session.head().setSourceField(f);
                 return;
            }
            for (Field f : mapping.getInputField()) {
                if ((docId == null && f.getDocId() != null)
                        || (docId != null && f.getDocId() == null)
                        || (docId != null && !docId.equals(f.getDocId()))) {
                    continue;
                }
                if (f.getPath() != null && f.getPath().equals(path)) {
                    session.head().setSourceField(f);
                    return;
                }
            }
        }

        private Field readFromGroup(FieldGroup group, String docId, String path) {
            if (group.getField() == null) {
                return null;
            }
            for (Field f : group.getField()) {
                if ((docId == null && f.getDocId() != null)
                        || (docId != null && f.getDocId() == null)
                        || (docId != null && !docId.equals(f.getDocId()))) {
                    continue;
                }
                if (f.getPath() != null && f.getPath().equals(path)) {
                    return f;
                }
                if (f instanceof FieldGroup) {
                    Field deeper = readFromGroup((FieldGroup)f, docId, path);
                    if (deeper != null) {
                        return deeper;
                    }
                }
            }
            return null;
        }

        @Override
        public Boolean isSupportedField(Field field) {
            // The field type doesn't matter for preview
            return true;
        }

        @Override
        public void processPreValidation(AtlasInternalSession session) throws AtlasException {
        }

        @Override
        public void processPreSourceExecution(AtlasInternalSession session) throws AtlasException {
        }

        @Override
        public void processPreTargetExecution(AtlasInternalSession session) throws AtlasException {
        }

        @Override
        public void writeTargetValue(AtlasInternalSession session) throws AtlasException {
        }

        @Override
        public void processPostSourceExecution(AtlasInternalSession session) throws AtlasException {
        }

        @Override
        public void processPostTargetExecution(AtlasInternalSession session) throws AtlasException {
        }

        @Override
        public Field cloneField(Field field) throws AtlasException {
            return null;
        }

    };

    @Override
    public Map<String, AtlasModule> getSourceModules() {
        return new HashMap<String, AtlasModule>() {
            private static final long serialVersionUID = 1L;

            @Override
            public AtlasModule get(Object key) {
                return previewModule;
            }
        };
    }

    @Override
    protected AtlasModule resolveModule(FieldDirection direction, Field field) {
        return previewModule;
    }

}
