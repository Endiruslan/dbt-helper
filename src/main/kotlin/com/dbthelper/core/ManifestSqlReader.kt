package com.dbthelper.core

import com.dbthelper.core.model.NodeSql
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.intellij.openapi.vfs.VirtualFile

/** Streams SQL fields for a single node from manifest.json without loading the full file. */
object ManifestSqlReader {

    private val jsonFactory = JsonFactory()

    fun readNodeSql(manifestFile: VirtualFile, nodeId: String): NodeSql {
        return try {
            manifestFile.inputStream.buffered().use { input ->
                jsonFactory.createParser(input).use { parser ->
                    readNodeSql(parser, nodeId) ?: NodeSql.EMPTY
                }
            }
        } catch (_: Exception) {
            NodeSql.EMPTY
        }
    }

    private fun readNodeSql(parser: JsonParser, nodeId: String): NodeSql? {
        if (parser.nextToken() != JsonToken.START_OBJECT) return null
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken != JsonToken.FIELD_NAME) continue
            when (parser.currentName) {
                "nodes" -> {
                    parser.nextToken()
                    val sql = readFromNodesObject(parser, nodeId)
                    if (sql != null) return sql
                }
                else -> parser.skipChildren()
            }
        }
        return null
    }

    private fun readFromNodesObject(parser: JsonParser, nodeId: String): NodeSql? {
        if (parser.currentToken != JsonToken.START_OBJECT) return null
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken != JsonToken.FIELD_NAME) continue
            val id = parser.currentName ?: continue
            parser.nextToken()
            if (id == nodeId) {
                return readSqlFields(parser)
            }
            parser.skipChildren()
        }
        return null
    }

    private fun readSqlFields(parser: JsonParser): NodeSql {
        var raw: String? = null
        var compiled: String? = null
        if (parser.currentToken != JsonToken.START_OBJECT) {
            parser.skipChildren()
            return NodeSql.EMPTY
        }
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken != JsonToken.FIELD_NAME) continue
            when (parser.currentName) {
                "raw_code", "raw_sql" -> {
                    parser.nextToken()
                    if (raw == null && parser.currentToken == JsonToken.VALUE_STRING) {
                        raw = parser.text
                    } else {
                        parser.skipChildren()
                    }
                }
                "compiled_code", "compiled_sql" -> {
                    parser.nextToken()
                    if (compiled == null && parser.currentToken == JsonToken.VALUE_STRING) {
                        compiled = parser.text
                    } else {
                        parser.skipChildren()
                    }
                }
                else -> parser.skipChildren()
            }
        }
        return NodeSql(raw = raw, compiled = compiled)
    }
}
