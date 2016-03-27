# LruCache实现原理

## 介绍

LRU是最近最少使用（Least Recently Used)缓存算法。它需要跟踪用户使用缓存的时间和次数，当缓存满时，它会丢弃掉最近最少使用的元素。

在Android3.1版本中引入了[LruCache](http://developer.android.com/intl/zh-cn/reference/android/util/LruCache.html)，如果需要兼容之前版本可以使用Support库中的同名类，也可以把Support库中该类拷贝出来放到自己代码中使用。

LruCache是实现缓存的一种数据结构，内部调用`LinkedHashMap`实现的LRU算法，还定义了7个记录缓存状态和操作次数的成员变量，对外提供缓存增删查改等方法，并抽象出了3个方法供子类扩展。


## 源码

我们将从以下6个关注点来看它在代码是如何实现的：

### LRU体现

先看它的构造方法：

```
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
    this.map = new LinkedHashMap<K, V>(0, 0.75f, true); // 这里 true 是LRU算法的关键
}

```

可以看到，初始化LruCache实质上是初始化`LinkedHashMap`，设置其构造方法中第三个参数为 true 才是真正的启用`LinkedHashMap`内置的顺序：从最近最少访问最近最多访问的顺序 ，具体可以看`LinkedHashMap`源码分析。

既然LruCache是一个数据结构，那它必然会对外提供操作数据（增删查改）的方法，我们接着看缓存写入方法：

### 写入缓存

```
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
```

在`put`方法中，检查传入key和value不为空后，会把key和value扔到map里面存起来，如果之前存在key和key对应的不为空的值previous，则会回滚size的值。因为是写入缓存键值对到缓存中，有可能当前缓存已经满了，所以需要检查缓存当前大小如果超过了最大值则会把最近最少使用的键值对移除掉。

方法返回null意味着插入前在缓存中不存在该key；否则意味着在插入前缓存中已存在该key，这种情况下会执行`entryRemoved()`，用户如果重写了`entryRemoved`可以实现显示的释放缓存值维持的资源。

我们再来看取缓存的逻辑：

### 读取缓存

```
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
 * 缺省情况下该方法返回值为null,除非用户重写了这个方法使得在缓存未被命中为对应的key提供了一个非空value.
 *
 * 该方法没有同步:其它线程同时操作缓存时肯呢过会导致脏数据.
 *
 * 如果该方法返回生成的value时map中已经有了对应的key和value,这个生成的value将会被丢弃并且在用户重写的entryRemoved方法中释放掉.这种情况
 *
 * 一般发生在多个线程在同一时间调用create生成同一个key对应的缓存值(会造成多个value被创建),也可能发生在一个线程调用put方法而另外一个线
 * 程正在为相同的key创建一个value.
 */
protected V create(K key) {
    return null;
}

```

简单来说，通过`get`方法可以获取之前存储在缓存中的值，如果之前没有存储过，用户可以重写`create`方法，`get`方法会把`create`方法中计算得出的值添加到缓存中。

因为 `create`是提供给用户使用的，这个方法可能执行时间过长再加上在多线程语境中用户有可能不同步缓存的操作状态导致脏数据，这种情况一旦发生，该方法将会丢弃掉用户通过`create`方法后写入的value。

### 删除缓存

接着看缓存删除相关逻辑：

```
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

```

`remove`方法会移除指定key对应的缓存值。

`trimeToSize`方法将会使缓存维持在指定大小。如果当前缓存大小不大于指定大小则直接退出；否则移除最近最少使用的缓存对象，同时更新当前缓存大小和回收次数直到当前缓存大小不大于指定大小 ；每次移除都会调用` entryRemoved`方法 。


### 调整缓存大小

使用`resize`可以调整缓存最大值，实际上也是调用`trimToSize`。

用户如果需要自己定义缓存大小单位，需要重写`sizeOf`方法，并确保对缓存对象的大小在缓存期间不会改变。

```
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
 *  自定义缓存单位时，用于计算缓存值占用的大小。默认返回1，这意味着缓存总大小是缓存对最大数。
 * <p/>
 * 键值对在缓存中,它的大小不能改变.
 */
protected int sizeOf(K key, V value) {
    return 1;
}

```


### 缓存命中率

在`toString()`方法中我们可以看到`缓存命中率 = 命中次数 /（命中次数 + 未命中次数）（次数都不为0情况下）`，实际中计算时我们需要结合`hitCount()`和`missCount()`方法来计算。

```
@Override public synchronized final String toString() {
        int accesses = hitCount + missCount;
        int hitPercent = accesses != 0 ? (100 * hitCount / accesses) : 0;
        return String.format("LruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]",
                maxSize, hitCount, missCount, hitPercent);
    }
```


为了方便理解，提供了中文注释版本的[LruCache类](LruCache.java)。


## 总结 

从上面源码中我们可以总结如下：

- LruCache是一种缓存数据结构，它对缓存对象维持着强引用。每次缓存被命中时，缓存元素会被移到队列头部；缓存已满情况下，添加新元素将会导致队列尾部的元素从缓存中释放出来从而可能被GC回收。
- LruCache的LRU算法实际上是依靠`LinkedHashMap`来实现的。
- LruCache是线程安全的。从源码中可以看出它公开的方法执行过程中都使用了`synchronized`。
- LruCache不允许使用空键或空值。调用`get`,`put`,`remove`返回空值意味着缓存中不存在相应的键。
- 重写`create`、`sizeOf`、`entryRemoved`等方法可以定制符合你业务的LruCache。




