package example;
import org.junit.jupiter.api.*;import org.junit.jupiter.params.*;import org.junit.jupiter.params.provider.*;import java.util.stream.*;
class LiveTest {
 @Test void slow()throws Exception{System.out.println("live test output");Thread.sleep(1000);}
 @ParameterizedTest @ValueSource(ints={1,2,3}) void parameter(int n)throws Exception{Thread.sleep(250);}
 @TestFactory Stream<DynamicTest> dynamic(){return IntStream.range(0,2).mapToObj(i->DynamicTest.dynamicTest("dynamic "+i,()->Thread.sleep(250)));}
 @Disabled @Test void skipped(){}
 @Test void failed(){Assertions.fail("expected probe failure");}
}