# Zkuste jeden úkol a jednu novou konverzaci

Malý experiment, ne migrace dokumentace: **jeden úkol, 2–3 ověřitelné fáze a jedna nová
konverzace.** Úspěch znamená správně dohledaný stav a doložený výsledek, ne jen
přesvědčivou odpověď modelu.

Tenhle repozitář je k tomu výchozí hřiště. Je to malé lokální výukové API v Javě 21
se SQLite, ne kompletní rezervační systém.

## Předpoklady

- **Přístup do PromptHelperu** a oprávnění pracovat s vlastní aplikací a plánem.
  Samoobslužná registrace není; o přístup se žádá u toho, kdo vám tenhle repozitář ukázal.
- **AI klient, který umí MCP.** Ověřeno ve VS Code s GitHub Copilotem; MCP umí i Claude
  Code a Cursor.
- **Java 21 a Git.** Maven Wrapper je součástí repozitáře, globální Maven není potřeba.

**Přístup a MCP jsou předpoklady, nikoli výsledek tohoto návodu.** Pokud něco z toho
chybí, vyřešte to nejdřív; nevymýšlejte náhradní identifikátory.

## Připojení MCP ve VS Code

Klíč uložte do systémové proměnné `PROMPTHELPER_API_KEY`. **Do souboru nepatří** —
takhle se nedá omylem commitnout.

`.vscode/mcp.json`:

```json
{
  "servers": {
    "prompthelper": {
      "type": "http",
      "url": "https://<HOST>/mcp",
      "headers": { "X-API-Key": "${env:PROMPTHELPER_API_KEY}" }
    }
  }
}
```

`<HOST>` a API klíč dostanete spolu s přístupem.

## Ověření repozitáře

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Windows PowerShell:

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD ".maven-user-home"
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Testy používají izolované dočasné SQLite databáze a nesahají na vaši lokální databázi.
Standardní sada nepotřebuje běžící broker; opt-in Kafka testy popisuje
[kafka-tests.md](kafka-tests.md).

## Volba úkolu

Vyberte si **malý, ohraničený a dosud nehotový** krok. Nepřepisujte, co už existuje.

Pokud hledáte inspiraci přímo tady: `GET /equipment` byl v původním plánu, ale
v `App.java` registrovaný není — viz [api-contract.md](api-contract.md). Existence
databázové metody `listEquipment()` z toho HTTP endpoint nedělá.

Pro vlastní projekt platí totéž: jeden úkol, u kterého poznáte, že je hotový.

## Prompt 1 — připravit malý vlastní plán

Hranaté závorky nahraďte svými hodnotami.

```text
Pracuji v repozitáři [CESTA_K_REPOZITÁŘI].
Pro aplikaci [APP_ID] v PromptHelperu navrhni plán pro malý úkol:
[STRUČNÝ_POPIS_ÚKOLU].

Přes MCP načti detail aplikace, její povinná pravidla a relevantní existující
use casy, rozhodnutí, poučení a otevřené úkoly. Nevytvářej duplicity.
Nejdřív ověř, jestli už úkol není hotový; pokud ano, nic nepřepisuj a řekni to.

Navrhni 2–3 fáze. Každá musí mít konkrétní výstup, test nebo jiný důkaz
a jasné podmínky dokončení. Ukaž mi návrh; bez potvrzení jej nevytvářej
ani nezačínej implementovat. Pokud nemáš přístup, zastav se a popiš,
který předpoklad chybí. Do promptů ani záznamů nevkládej tajemství.
```

Po schválení nechte plán vytvořit a uložte si vrácené `planId`. Proveďte **jednu fázi**,
ověřte změnu testem a nechte zapsat skutečný výsledek. Zbývá-li něco, popište konkrétní
nedodělek. Pak původní konverzaci zavřete.

## Prompt 2 — nová konverzace, žádný starý chat

```text
Pokračuj v repozitáři [CESTA_K_REPOZITÁŘI].
appId: [APP_ID]
planId: [PLAN_ID]

Přes MCP načti další připravenou fázi, pravidla aplikace a relevantní kontext plánu.
Dohledávej jen entity potřebné pro tu fázi. Nejdřív mi shrň:
1. Která fáze je další a jaký má mít výsledek?
2. Co je doloženě hotové a co zůstává otevřené?
3. Které pravidlo, rozhodnutí nebo poučení ovlivňuje postup?
4. Jak výsledek ověříš?

Zatím neměň kód ani stav. Pokud je plán dokončený, řekni to a nepředstírej nový úkol.
Pokud si plán a kód odporují, popiš rozpor a zeptej se.
```

Shrnutí porovnejte se skutečností. **Až potom** povolte provedení té jediné fáze.
Vyžadujte testy a dohledatelnou změnu; zápis přes MCP není sám o sobě důkaz implementace.

## Vyhodnocení

- Našla nová konverzace správnou fázi bez kopírování starého chatu?
- Dohledala relevantní pravidla a neztratila otevřený úkol?
- Nezaměnila uložený stav za člověkem schválený?
- Sedí její tvrzení s repozitářem a testy?
- Pomohlo to proti vašemu dosavadnímu postupu?

Pokud ne, pojmenujte konkrétní chybějící informaci nebo zbytečnou režii. Jeden experiment
není důkaz univerzální produktivity. **Pro malý jednorázový úkol může být dobře vedený
Markdown lepší volba** — smysl to začne dávat tam, kde se ke stejné aplikaci vracíte
opakovaně a ve více konverzacích.
