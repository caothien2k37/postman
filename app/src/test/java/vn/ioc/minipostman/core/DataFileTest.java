package vn.ioc.minipostman.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import vn.ioc.minipostman.core.importexport.DataFile;
import vn.ioc.minipostman.core.importexport.ImportException;

public class DataFileTest {

    @Test
    public void csvWithQuotesAndCrLf() throws Exception {
        String csv = "user,note\r\nalice,\"hello, \"\"world\"\"\"\r\nbob,\r\n\r\ncarol,last";
        List<Map<String, String>> rows = DataFile.parse(csv, "data.csv");
        assertEquals(3, rows.size());
        assertEquals("alice", rows.get(0).get("user"));
        assertEquals("hello, \"world\"", rows.get(0).get("note"));
        assertEquals("", rows.get(1).get("note"));
        assertEquals("carol", rows.get(2).get("user"));
    }

    @Test
    public void jsonArrayOfObjectsStringifiesValues() throws Exception {
        List<Map<String, String>> rows = DataFile.parse("[{\"id\":1,\"ok\":true,\"name\":\"a\"},{\"id\":2}]", "d.json");
        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).get("id"));
        assertEquals("true", rows.get(0).get("ok"));
    }

    @Test
    public void badInputIsRejected() {
        for (String bad : new String[]{"", "onlyheader", "[1,2]", "a,b\n\"unterminated"}) {
            try {
                DataFile.parse(bad, "x");
                fail(bad);
            } catch (ImportException expected) {
                // ok
            }
        }
    }
}
