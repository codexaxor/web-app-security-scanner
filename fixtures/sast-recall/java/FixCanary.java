import javax.xml.parsers.DocumentBuilderFactory;

// Synthetic fixture (M8.15 review B1): the XXE rule's autofix (extra.fix) is rewritten from the matched source,
// so it would carry this obviously fake canary identifier if the sanitize let extra.fix through.
public class FixCanary {
    public void parse(String xml) throws Exception {
        DocumentBuilderFactory L3AKfixCanary6060 = DocumentBuilderFactory.newInstance();
        L3AKfixCanary6060.newDocumentBuilder().parse(xml);
    }
}
