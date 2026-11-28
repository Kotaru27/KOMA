package com.koma.reader;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class RulesTest {
 @Test public void ordering(){List<String> a=new ArrayList<>(List.of("Chapter 10","Chapter 2","Chapter 1"));a.sort(Rules::natural);assertEquals(List.of("Chapter 1","Chapter 2","Chapter 10"),a);}
 @Test public void jumpNotComplete(){assertNotEquals(Rules.FULL,Rules.coverage(0,150,160,160));}
 @Test public void fullCoverage(){int n=Rules.coverage(0,0,80,160);assertEquals(Rules.FULL,Rules.coverage(n,80,160,160));}
 @Test public void deletionFailsClosed(){Set<String> a=Set.of("1","2"), b=Set.of("1");assertFalse(Rules.deletionEligible(false,true,true,a,b));assertFalse(Rules.deletionEligible(true,true,true,a,a));assertFalse(Rules.deletionEligible(false,false,true,a,a));assertFalse(Rules.deletionEligible(false,true,false,a,a));assertTrue(Rules.deletionEligible(false,true,true,a,a));}
 @Test public void folderBoundary(){assertTrue(Rules.strictDescendant("primary:Books","primary:Books/Manga"));assertFalse(Rules.strictDescendant("primary:Books","primary:Books"));assertFalse(Rules.strictDescendant("primary:Books","primary:Books2/Manga"));assertFalse(Rules.strictDescendant("primary:Books","primary:Books/../Other"));assertFalse(Rules.strictDescendant("ABCD:Books","ABCD:Books/Manga"));}
 @Test public void changedFolderNeverEligible(){Set<String> old=Set.of("1","2");assertTrue(Rules.stableSnapshot(old,old,old));assertFalse(Rules.stableSnapshot(old,old,Set.of("1","2","3")));assertFalse(Rules.stableSnapshot(old,old,Set.of("1")));assertFalse(Rules.stableSnapshot(old,Set.of("1","changed"),Set.of("1","changed")));assertFalse(Rules.stableSnapshot(Set.of(),Set.of(),Set.of()));}
 @Test public void shortPageCoverage(){assertEquals(Rules.FULL,Rules.coverage(0,0,8,8));}
 @Test public void allSortModes(){assertTrue(Rules.mangaOrder(0,"Manga 2","Manga 10",1,2,3,4,5,6)<0);assertTrue(Rules.mangaOrder(3,"Manga 2","Manga 10",1,2,3,4,5,6)>0);assertTrue(Rules.mangaOrder(1,"A","B",1,2,3,4,5,6)>0);assertTrue(Rules.mangaOrder(2,"A","B",1,2,3,4,5,6)>0);assertTrue(Rules.mangaOrder(4,"A","B",1,2,3,4,5,6)<0);assertTrue(Rules.mangaOrder(5,"A","B",1,2,3,4,5,6)>0);assertTrue(Rules.mangaOrder(6,"A","B",1,2,3,4,5,6)<0);}
}
