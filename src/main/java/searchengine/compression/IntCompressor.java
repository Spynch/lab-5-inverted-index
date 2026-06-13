package searchengine.compression;

public interface IntCompressor {
    byte[] compress(int[] values);

    int[] decompress(byte[] bytes);
}
