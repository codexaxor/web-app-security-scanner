import javax.xml.parsers.DocumentBuilderFactory;

public class Xxe {
    public void parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.newDocumentBuilder().parse(xml);
    }
}
