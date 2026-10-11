package cn.iocoder.yudao.module.quant.service;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.quant.engine.DatasetRegistry;
import java.util.*;

/** Sort every map, including nested Map.of values whose iteration order can change between JVMs. */
public final class HistoricalEvidence {
    private HistoricalEvidence(){}
    private static Object sorted(Object value){
        if(value instanceof Map<?,?> map){
            var result=new TreeMap<String,Object>();map.forEach((key,item)->result.put(key.toString(),sorted(item)));return result;
        }
        if(value instanceof List<?> list)return list.stream().map(HistoricalEvidence::sorted).toList();
        return value;
    }
    public static String hash(Object value){return DatasetRegistry.hash(JsonUtils.toJsonByte(sorted(value)));}
}
