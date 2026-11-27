package com.koma.reader;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class RulesTest {
 @Test public void ordering(){List<String> a=new ArrayList<>(List.of("Chapter 10","Chapter 2","Chapter 1"));a.sort(Rules::natural);assertEquals(List.of("Chapter 1","Chapter 2","Chapter 10"),a);}
 @Test public void jumpNotComplete(){assertNotEquals(Rules.FULL,Rules.coverage(0,150,160,160));}
 @Test public void fullCoverage(){int n=Rules.coverage(0,0,80,160);assertEquals(Rules.FULL,Rules.coverage(n,80,160,160));}
 @Test public void deletionFailsClosed(){Set<String> a=Set.of("1","2"), b=Set.of("1");assertFalse(Rules.deletionEligible(false,true,true,a,b));assertFalse(Rules.deletionEligible(true,true,true,a,a));assertFalse(Rules.deletionEligible(false,false,true,a,a));assertFalse(Rules.deletionEligible(false,true,false,a,a));assertTrue(Rules.deletionEligible(false,true,true,a,a));}
}
