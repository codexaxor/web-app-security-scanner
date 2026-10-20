public class EvalSafe {
  Object run(ExpressionParser parser) {
    return parser.parseExpression("1 + 1").getValue();
  }
}
