package com.checkmarx.engine.domain;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.junit4.SpringRunner;

import com.google.common.collect.Lists;

/**
 * 
 * @author rjgey
 *
 */
@RunWith(SpringRunner.class)
public class EngineSizeTests {

    private static final Logger log = LoggerFactory.getLogger(EngineSizeTests.class);
    
    public static final EngineSize SMALL = new EngineSize("S", 0, 99999);
    public static final EngineSize MEDIUM = new EngineSize("M", 100000, 499999);
    public static final EngineSize LARGE = new EngineSize("L", 500000, 999999999);
    public static final EngineSize OVERLAP = new EngineSize("O", 50000, 1000000);

    @Test
    public void testIsOverlap() {
        log.trace("testIsOverlap()");
        
        List<EngineSize> list = Lists.newArrayList();
        assertFalse(EngineSize.isOverlap(list));

        list.add(LARGE);
        assertFalse(EngineSize.isOverlap(list));
        
        list.add(SMALL);
        assertFalse(EngineSize.isOverlap(list));
        
        list.add(MEDIUM);
        assertFalse(EngineSize.isOverlap(list));

        list.add(OVERLAP);
        assertTrue(EngineSize.isOverlap(list));
    }

}
