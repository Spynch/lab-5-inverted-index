package searchengine.query;

public interface QueryParser {
    QueryNode parse(String query);
}
