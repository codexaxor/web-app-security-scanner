export function run(code: string): void {
  eval(code);
}

export function sink(hash: string): void {
  document.write(hash);
}
