package yumefusaka.envoymart.knowledge.service;

import yumefusaka.envoymart.knowledge.model.KnowledgeSnippet;

import java.util.List;

public interface KnowledgeService {

    List<KnowledgeSnippet> retrieve(String query, int limit);
}
