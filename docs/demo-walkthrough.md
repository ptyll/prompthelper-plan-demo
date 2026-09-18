# PromptHelper: od malého zadání k pokračování v nové session

GearReserve je malé lokální výukové API v Javě 21 se SQLite, nikoli kompletní rezervační systém. Příběh ukazuje práci s plánem přes PromptHelper MCP: malé zadání, ohraničené fáze, vědomě odložený krok a pokračování v nové session na základě uloženého kontextu a skutečného stavu repozitáře.

**Současný `main` už obsahuje filtr rezervací podle stavu.** Tento walkthrough vysvětluje etapový příběh; není novým replayem implementace filtru na dnešním kódu. Pro vlastní pokus si zvolte **nový malý úkol a vlastní plán**. Existující funkci nemažte ani znovu neimplementujte jen kvůli demonstraci.

Níže jsou návrhy kopírovatelných promptů, nikoli citace historického přepisu konverzace. Neobsahují výsledky nového běhu testů ani důkaz konkrétního běhu PromptHelperu. Všechny zástupné hodnoty v `<...>` nahraďte vlastními údaji ve své session; do veřejné dokumentace nepatří soukromá ID, URL, přístupové údaje ani screenshoty.

## 1. Zadání a hranice

Výchozí zadání příběhu: jednoduché API pro vytvoření rezervace vybavení, její schválení a výpis. Vybavení je pevně seedované, žadatelé používají smyšlené aliasy. Není potřeba frontend, přihlášení ani rozšiřování domény.

Původní plán zahrnoval i `GET /equipment`, ale současný kód tento endpoint neregistruje. Databázový seed není HTTP endpoint. Rozdíl mezi plánem a skutečností uvádí [API kontrakt](api-contract.md); při prezentaci tento bod nevydávejte za dokončenou funkci.

Prompt pro založení vlastního experimentu:

```text
Pracuj v repozitáři <REPO_PATH>. Nejprve přečti AGENTS.md, README.md,
docs/api-contract.md a relevantní kód a testy. Je to malé lokální výukové
API v Javě 21 se SQLite, ne kompletní rezervační systém.

Můj nový ohraničený úkol je <NEW_TASK>.
Akceptační podmínky: <ACCEPTANCE_CRITERIA>.
Nejdřív ověř, zda už není implementován; pokud ano, nic nepřepisuj a požádej
o jiný úkol. Stávající status filtr již na main je.

Přes dostupné nástroje PromptHelper MCP načti aplikaci <APP_ID> a připrav
vlastní nový plán pro tento úkol. Rozděl jej na malé fáze s ověřením.
Zatím neimplementuj; předlož rozsah a fáze ke schválení. Nepřidávej frontend,
autentizaci ani nesouvisející funkce. Necommituj a nepushuj.
```

`<APP_ID>` označuje vaši aplikaci v PromptHelperu, nikoli identifikátor dodávaný tímto repozitářem. Po založení vlastního plánu si ponechte jeho `<PLAN_ID>` pro navazující session. Pokud MCP není dostupné nebo přístup k aplikaci chybí, agent má tento blokátor oznámit, ne si kontext domýšlet.

## 2. Fáze a záměrné odložení

Výukový příběh lze vyprávět v těchto etapách:

1. **Základ:** lokální spuštění, health endpoint, SQLite a bezpečný seed.
2. **Malé doménové kroky:** vytvoření rezervace podle schvalovacího pravidla, schválení a základní výpis; každá změna má vlastní ověření.
3. **Vědomé zastavení:** filtr `Pending` / `Approved` zůstává v plánu jako jasně pojmenovaný nedokončený krok. Uloží se požadované chování, omezení a co zbývá ověřit.
4. **Nová session:** dostane repo, `appId` a `planId`, načte uložený kontext, porovná jej s kódem, dokončí schválený krok, otestuje a zapíše skutečný výsledek.

Tento seznam je osnova příběhu, ne tvrzení o přesných názvech uložených fází či kompletním dokončení původního kontraktu. Odložení filtru patří do dřívější etapy příběhu; **na dnešním `main` už filtr nečeká na implementaci**.

Pro vlastní plán použijte namísto filtru jiný dosud nehotový, schválený krok:

```text
Repo: <REPO_PATH>
appId: <APP_ID>
planId: <PLAN_ID>

Načti plán a proveď pouze schválenou fázi <PHASE_TO_IMPLEMENT>.
Krok <DEFERRED_TASK> záměrně ponech nedokončený; neimplementuj jej předem.
Po změně kódu spusť ověření podle AGENTS.md s izolovanou dočasnou databází.
Do vlastního plánu zapiš, co se skutečně změnilo, jaké příkazy opravdu běžely,
jejich skutečný výsledek a co zůstává neověřené nebo blokované.
U odloženého kroku ulož akceptační podmínky <DEFERRED_ACCEPTANCE_CRITERIA>
a omezení. Nepřepisuj stav jiné fáze a neoznačuj celý plán za hotový.
Necommituj a nepushuj.
```

## 3. Nová session načte kontext, místo aby hádala

V nové session není nutné vkládat přepis předchozího chatu. Agent dostane umístění repozitáře a identifikátory vlastního plánu. Uložený plán vysvětluje záměr, ale aktuální kód určuje, co už existuje. Nesoulad má agent pojmenovat před úpravami.

```text
Pokračuj v mém schváleném úkolu v nové session.
Repo: <REPO_PATH>
appId: <APP_ID>
planId: <PLAN_ID>

Nejdřív přečti AGENTS.md a dokumentaci repozitáře. Přes dostupné nástroje
PromptHelper MCP načti aplikaci, plán, jeho fáze, uložené poznámky k výsledkům
a relevantní pravidla, rozhodnutí a poučení. Z tohoto kontextu sám rekonstruuj
úkol a urči další připravenou fázi vrácenou plánem; zadání neodhaduj.
Porovnej načtený kontext s aktuálním kódem a testy. Stručně shrň cíl, omezení,
hotové kroky, zbývající práci a způsob ověření. Pokud plán nelze načíst,
zastav a oznam to. Pokud je další připravená fáze vrácená plánem už hotová,
žádná není připravena nebo si plán a kód odporují, nic slepě neimplementuj;
popiš situaci a požádej o upřesnění.

Jestliže další připravená fáze vrácená plánem odpovídá schválenému rozsahu
a dosud chybí, implementuj pouze tuto fázi a odpovídající testy. Po změně kódu spusť Maven Wrapper verify podle
AGENTS.md. Používej izolované dočasné SQLite databáze, nemaž lokální data.
Potom do stejného vlastního plánu zapiš skutečný výsledek, změněné soubory,
provedené ověření a případné zbývající blokátory. Necommituj a nepushuj.
```

## 4. Ověření a zápis výsledku

V příběhu filtru je cílem ověřit výpis bez filtru, oba povolené stavy a odmítnutí neplatného stavu. Dnešní repozitář už obsahuje příslušný [HTTP test](../src/test/java/dev/gearreserve/ListReservationsHttpTest.java). Jeho existence není dokladem nového úspěšného spuštění.

Pro vlastní změnu kódu jsou příkazy ověření uvedeny v [README](../README.md#build-and-test). Při pouhé úpravě dokumentace se tento příběh nepovažuje za provedenou implementaci ani za nový testovací běh.

Prompt pro uzavření vlastní fáze:

```text
V plánu <PLAN_ID> aplikace <APP_ID> aktualizuj jen fázi <PHASE_ID>.
Uveď stručně zadání, skutečně provedené změny a změněné soubory.
Zapiš přesné příkazy, které jsi opravdu spustil, a jejich skutečné výsledky.
Pokud testy neběžely, výslovně to napiš; nepřidávej odhadované počty ani úspěch.
Odděl dokončené, neověřené a odložené položky. Fázi označ za dokončenou jen
při splnění jejích akceptačních podmínek; jinak popiš konkrétní zbývající práci.
Do veřejného shrnutí nevkládej soukromá ID, URL, screenshoty ani přístupové údaje.
```

## Co si má publikum odnést

- Plán uchovává záměr, hranice a další krok; nenahrazuje kontrolu zdrojového kódu.
- Nová session si načte kontext a nejprve ověří, zda se zadání shoduje se skutečností.
- Odložený krok je explicitní rozhodnutí, ne skrytý nedostatek.
- Implementace, provedené testy a zápis výsledku jsou tři různé věci; nic z toho se nemá jen předpokládat.
- Příběh filtru vysvětluje postup. Samostatný pokus nad dnešním `main` vyžaduje nový úkol a vlastní plán.
