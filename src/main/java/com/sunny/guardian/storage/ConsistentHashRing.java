package com.sunny.guardian.storage;


import com.google.common.hash.Hashing;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConsistentHashRing {

    private static final String DELIMITER = "#";
    private final int virtualNodes;
    private final SortedMap<Long, JedisPool> ring;
    private final ReadWriteLock rwLock;
    private final Lock readLock;
    private final Lock writeLock;


    public ConsistentHashRing(int virtualNodes) {
        this.virtualNodes = virtualNodes;
        this.ring = new TreeMap<>();
        this.rwLock = new ReentrantReadWriteLock(); // not allowing fairness due to performance issues. Our writes will anyway be rare, and they are not user facing
        this.readLock = this.rwLock.readLock();
        this.writeLock = this.rwLock.writeLock();
    }

    public void addNode(String nodeId, JedisPool jedisPool) {
        writeLock.lock();
        try {
            for (int i = 0; i < this.virtualNodes; i++) {
                String key = nodeId + DELIMITER + i;
                this.ring.put(this.getHash(key), jedisPool);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void removeNode(String nodeId) {
        writeLock.lock();
        try {
            for (int i = 0; i < this.virtualNodes; i++) {
                String key = nodeId + DELIMITER + i;
                this.ring.remove(this.getHash(key));
            }
        } finally {
            writeLock.unlock();
        }
    }

    public JedisPool getPool(String key) {
        readLock.lock();
        try {
            if (this.ring.isEmpty()) {
                return null;
            }
            Long hashedKey = this.getHash(key);
            if (this.ring.containsKey(hashedKey)) {
                return this.ring.get(hashedKey);
            } else {
                SortedMap<Long, JedisPool> greaterThanHashedKeyRing = this.ring.tailMap(hashedKey);
                if (greaterThanHashedKeyRing.isEmpty()) {
                    return this.ring.get(this.ring.firstKey());
                } else {
                    return this.ring.get(greaterThanHashedKeyRing.firstKey());
                }
            }
        } finally {
            readLock.unlock();
        }

    }

    private Long getHash(String key) {
        return Hashing.murmur3_32_fixed().hashString(key, StandardCharsets.UTF_8).asLong();
    }

}
