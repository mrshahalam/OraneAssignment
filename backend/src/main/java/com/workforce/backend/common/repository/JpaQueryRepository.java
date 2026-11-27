package com.workforce.backend.common.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.hibernate.query.NativeQuery;
import org.springframework.dao.DataIntegrityViolationException;
import com.workforce.backend.common.NotFoundException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaQueryRepository {
    private final EntityManager entityManager;

    public JpaQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> queryForList(String sql, Object... parameters) {
        NativeQuery<?> query = nativeQuery(sql, parameters);
        query.setTupleTransformer((tuple, aliases) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < aliases.length; i++) {
                row.put(aliases[i].toLowerCase(Locale.ROOT), tuple[i]);
            }
            return row;
        });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) query.getResultList();
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> queryForMap(String sql, Object... parameters) {
        List<Map<String, Object>> rows = queryForList(sql, parameters);
        if (rows.isEmpty()) throw new NotFoundException("Requested record was not found");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> queryForUpdate(String sql, Object... parameters) {
        return queryForMap(sql, parameters);
    }

    @Transactional
    public <T> T queryForObject(String sql, Class<T> resultType, Object... parameters) {
        Query query = bind(entityManager.createNativeQuery(sql), parameters);
        Object value = query.getSingleResult();
        return convert(value, resultType);
    }

    @Transactional(readOnly = true)
    public <T> T queryForNullableObject(String sql, Class<T> resultType, Object... parameters) {
        Query query = bind(entityManager.createNativeQuery(sql), parameters);
        List<?> results = query.getResultList();
        return results.isEmpty() ? null : convert(results.get(0), resultType);
    }

    @Transactional
    public int update(String sql, Object... parameters) {
        try {
            return bind(entityManager.createNativeQuery(sql), parameters).executeUpdate();
        } catch (PersistenceException ex) {
            if (hasConstraintViolation(ex)) {
                throw new DataIntegrityViolationException("Database constraint rejected the operation", ex);
            }
            throw ex;
        }
    }

    private boolean hasConstraintViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) return true;
        }
        return false;
    }

    private NativeQuery<?> nativeQuery(String sql, Object... parameters) {
        NativeQuery<?> query = entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
        for (int i = 0; i < parameters.length; i++) {
            query.setParameter(i + 1, parameters[i]);
        }
        return query;
    }

    private Query bind(Query query, Object... parameters) {
        for (int i = 0; i < parameters.length; i++) {
            query.setParameter(i + 1, parameters[i]);
        }
        return query;
    }

    private <T> T convert(Object value, Class<T> type) {
        if (value == null) return null;
        if (type.isInstance(value)) return type.cast(value);
        if (value instanceof Number number) {
            if (type == Long.class) return type.cast(number.longValue());
            if (type == Integer.class) return type.cast(number.intValue());
            if (type == Double.class) return type.cast(number.doubleValue());
            if (type == Float.class) return type.cast(number.floatValue());
        }
        if (type == String.class) return type.cast(value.toString());
        throw new IllegalArgumentException("Cannot convert query result " + value.getClass() + " to " + type);
    }
}
