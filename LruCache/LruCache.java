import java.util.LinkedHashMap;
import java.util.Map;


public class LruCache<K, V> {
    private final LinkedHashMap<K, V> map;

    private int size; // 当前缓存大小
    private int maxSize; // 缓存容量最大值

    private int putCount; // put次数
    private int createCount; // create次数
    private int evictionCount; // 回收次数
    private int hitCount; // 命中缓存次数
    private int missCount; // 未命中缓存次数

    /**
     * 如果没有重写 sizeOf() 方法, maxSize 代表的是缓存键值对最大个数.其它情况下,maxSize 代表的是缓存中键值对总和的最大大小.
     */
    public LruCache(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<K, V>(0, 0.75f, true); // 这里设为 true 是LRU算法的关键
    }

    /**
     * 重新调整缓存的大小
     *
     * @param maxSize 新的缓存最大值
     */
    public void resize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize <= 0");
        }

        synchronized(this) {
            this.maxSize = maxSize;
        }
        trimToSize(maxSize);
    }

    /**
     * 返回缓存中给定key对应的value,如果该value不存在但用户重写了create方法也会返回create具体实现中返回的value.如果有不为空的value返回
     * ,它将会被移动到队列头部.缓存中没有该key对应的value并且用户也没有实现create方法将会返回null.
     */
    public final V get(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V mapValue;
        synchronized(this) {
            mapValue = map.get(key);
            // 缓存中如果存在key对应的value说明已经命中缓存,否则没有命中缓存
            if (mapValue != null) {
                hitCount++;
                return mapValue;
            }
            missCount++;
        }

        /*
         * 用户重写的create方法在执行时,map可能正被另外一个线程写入这会导致map与之前不一致,这种情况下,我们的措施是释放掉用户重写create
         * 返回的value并保留另外一个线程写入的value.
         */

        V createdValue = create(key);
        if (createdValue == null) {
            return null;
        }

        synchronized(this) {
            createCount++;
            mapValue = map.put(key, createdValue);

            if (mapValue != null) {
                // 执行到这里意味着有多线程造成的数据冲突,需要撤销后添加进来的value
                map.put(key, mapValue);
            } else {
                size += safeSizeOf(key, createdValue);
            }
        }

        if (mapValue != null) {
            entryRemoved(false, key, createdValue, mapValue);
            return mapValue;
        } else {
            trimToSize(maxSize);
            return createdValue;
        }
    }

    /**
     * 缓存指定key对应的value , value将被移动到缓存队列头部.
     *
     * @返回 插入前 key 对应的 value,大部分情况为空
     */
    public final V put(K key, V value) {
        // key和value不能为空
        if (key == null || value == null) {
            throw new NullPointerException("key == null || value == null");
        }

        V previous;
        synchronized(this) {
            putCount++;
            size += safeSizeOf(key, value);
            // 如果插入value前缓存中存在key对应的value(previous),则需要回滚 size 大小
            previous = map.put(key, value);
            if (previous != null) {
                size -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, value);
        }

        // 检查缓存是否达到最大值,如果达到就按照LRU算法移除最近最少使用元素
        trimToSize(maxSize);
        return previous;
    }

    /**
     * 移除最近最少使用的键值对使剩余的总键值对不大于指定大小。
     *
     * @param maxSize 返回之前缓存最大值.也可能为-1这种清空将清空缓存所有元素.
     */
    public void trimToSize(int maxSize) {
        while (true) {
            K key;
            V value;
            synchronized(this) {
                if (size < 0 || (map.isEmpty() && size != 0)) {
                    throw new IllegalStateException(
                            getClass().getName() + ".sizeOf() is reporting inconsistent results!");
                }

                // 缓存"未满"时直接退出
                if (size <= maxSize || map.isEmpty()) {
                    break;
                }

                // 缓存大于指定maxSize,会先移除最近最少使用的值，同时更新size和evictionCount
                Map.Entry<K, V> toEvict = map.entrySet().iterator().next();
                key = toEvict.getKey();
                value = toEvict.getValue();
                map.remove(key);
                size -= safeSizeOf(key, value);
                evictionCount++;
            }

            entryRemoved(true, key, value, null);
        }
    }

    /**
     * 移除指定key对应的缓存对
     *
     * @return 移除前key对应的value
     */
    public final V remove(K key) {
        if (key == null) {
            throw new NullPointerException("key == null");
        }

        V previous;
        synchronized(this) {
            previous = map.remove(key);
            if (previous != null) {
                size -= safeSizeOf(key, previous);
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, null);
        }

        return previous;
    }

    /**
     * 键值对被覆盖或移除时会调用该方法.使用 remove 移除缓存元素 或 使用 put 时覆盖缓存元素时该方法会被调用用于腾挪出空间. 缺省情况下空实现.
     * <p/>
     * <p/>
     * 该方法没有同步,方法执行时其它线程可能会使用缓存的造成脏数据,重写缓存操作时记得加上同步
     *
     * @param evicted  true 意味着缓存元素被移除用于腾出空间 , false 意味着是由于 put 或 remove 方法导致.
     * @param newValue key对应的新value,如果该value存在的话.使用 put时该值不为空,使用remove或其它方法时该值为空.
     */
    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {
    }

    /**
     * 缺省情况下该方法返回值为null,除非用户重写了这个方法使得在缓存未被命中为对应的key提供了一个非空value.
     * <p/>
     * <p/>
     * 该方法没有同步:其它线程同时操作缓存时肯呢过会导致脏数据.
     * <p/>
     * <p/>
     * 如果该方法返回生成的value时map中已经有了对应的key和value,这个生成的value将会被丢弃并且在用户重写的entryRemoved方法中释放掉.这种情况
     * 一般发生在多个线程在同一时间调用create生成同一个key对应的缓存值(会造成多个value被创建),也可能发生在一个线程调用put方法而另外一个线
     * 程正在为相同的key创建一个value.
     */
    protected V create(K key) {
        return null;
    }

    private int safeSizeOf(K key, V value) {
        int result = sizeOf(key, value);
        if (result < 0) {
            throw new IllegalStateException("Negative size: " + key + "=" + value);
        }
        return result;
    }

    /**
     *  自定义缓存单位时，用于计算缓存值占用的大小。默认返回1，这意味着缓存总大小是缓存对最大数。
     * <p/>
     * <p/>
     * 键值对在缓存中,它的大小不能改变.
     */
    protected int sizeOf(K key, V value) {
        return 1;
    }

    /**
     * 清空所有缓存,trimToSize 的极端情况,对每个需要移除的键值对调用 entryRemoved 方法 .
     */
    public final void evictAll() {
        trimToSize(-1);
    }

    /**
     * 自定义缓存单位时，用于计当前算缓存值占用的大小。默认返回1，这意味着缓存总大小为缓存键值对总数。
     */
    public synchronized final int size() {
        return size;
    }

    /**
     * 返回缓存最大值,如果用户没有实现 sizeOf 方法,返回的是键值对最大总数
     */
    public synchronized final int maxSize() {
        return maxSize;
    }

    /**
     * 返回缓存命中次数
     */
    public synchronized final int hitCount() {
        return hitCount;
    }

    /**
     * 返回缓存未被命中的次数
     */
    public synchronized final int missCount() {
        return missCount;
    }

    /**
     * 返回 crate 方法调用次数(非空实现)
     */
    public synchronized final int createCount() {
        return createCount;
    }

    /**
     * 返回 put 方法调用次数
     */
    public synchronized final int putCount() {
        return putCount;
    }

    /**
     * 返回缓存值被回收的次数
     */
    public synchronized final int evictionCount() {
        return evictionCount;
    }

    /**
     * 返回当前缓存的一份拷贝,里面元素按最近最少访问到最近最多访问排序。
     */
    public synchronized final Map<K, V> snapshot() {
        return new LinkedHashMap<K, V>(map);
    }

    @Override
    public synchronized final String toString() {
        int accesses = hitCount + missCount;
        int hitPercent = accesses != 0 ? (100 * hitCount / accesses) : 0;
        return String.format("LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]", maxSize, hitCount, missCount,
                hitPercent);
    }
}
