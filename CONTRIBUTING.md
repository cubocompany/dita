# Como contribuir

Obrigado pelo interesse no Dita! Contribuições são bem-vindas: correções, suporte a mais apps,
traduções e ideias.

## Fluxo

1. Para algo maior que uma correção pequena, abra uma [issue](https://github.com/cubocompany/dita/issues)
   antes para combinarmos a ideia.
2. Faça um fork, crie um branch a partir de `main` e envie um pull request.
3. Descreva o que mudou e como testou (aparelho, versão do Android e app em que ditou).

O branch `main` é protegido: ninguém envia commits direto para ele. Todo PR passa pela revisão do
mantenedor, que é quem faz o merge.

## Princípios do projeto

- **Leve:** Kotlin nativo, sem bibliotecas externas. PRs que adicionam dependências dificilmente
  serão aceitos.
- **Privado:** nada de telemetria, contas ou serviços de terceiros embutidos. O áudio vai só para o
  servidor que a pessoa configurou.
- **Direto:** sem cadastro ou onboarding longo.

## Compilar

Veja a seção [Compilar](README.md#compilar) do README.

## Licença

Ao contribuir, você concorda que seu código será distribuído sob a [licença MIT](LICENSE).
