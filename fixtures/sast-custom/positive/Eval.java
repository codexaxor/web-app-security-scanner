public class Eval {
  Object run(ExpressionParser parser, String userInput) {
    return parser.parseExpression(userInput).getValue();
  }
}
