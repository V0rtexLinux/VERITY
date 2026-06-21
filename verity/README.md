# Verity Mod — Fabric 1.20.1

Recriação fiel do ARG **Verity** criado por [@ThatMob](https://www.youtube.com/@ThatMob) para Minecraft Java Edition.

> *"Um amiguinho prestável — mas não perguntes sobre a aldeia a leste, e não o deixes..."*

---

## Requisitos

| Dependência | Versão |
|---|---|
| Minecraft Java Edition | 1.20.1 |
| Fabric Loader | ≥ 0.14.22 |
| Fabric API | 0.87.2+1.20.1 |
| GeckoLib | 4.3.1 (incluído no jar) |
| Vosk (modelo de voz) | ver abaixo |
| Java | 17+ |
| Microfone | Qualquer microfone reconhecido pelo sistema |

---

## Instalação — Modelo de Voz (Vosk)

O reconhecimento de voz é **100% offline** — sem API keys, sem internet.

1. Vai a **https://alphacephei.com/vosk/models**
2. Descarrega um modelo pequeno:
   - **Português:** `vosk-model-small-pt-0.3` (~35 MB)
   - **Inglês:** `vosk-model-small-en-us-0.15` (~40 MB)
3. Extrai a pasta do modelo para dentro da tua pasta `.minecraft/`:
   ```
   .minecraft/
   └── vosk-model/          ← pasta do modelo aqui (com este nome exato)
       ├── am/
       ├── conf/
       ├── graph/
       └── ...
   ```
4. Inicia o Minecraft — o mod carrega o modelo automaticamente.

> **Indicador de estado** no canto superior esquerdo:
> - `🎤 "Hey Verity"...` — pronto, à espera da palavra de ativação
> - `🎤 Ouvindo...` (verde pulsante) — a gravar o teu comando
> - `🎤 Processando...` — a enviar para o servidor

---

## Como Compilar

```bash
git clone <repo>
cd verity-mod
./gradlew build
# Output: build/libs/verity-1.0.0.jar
```

---

## Falar com a Verity

**Palavra de ativação (voz ou chat):** `"Hey Verity"` ou `"Verity"`

Não precisas de escrever nada — fala diretamente ao microfone. O mod reconhece a tua voz e envia o comando ao servidor sem aparecer no chat.

### Comandos úteis

| O que dizes | O que a Verity faz |
|---|---|
| `encontra diamantes` | Escaneia 64 blocos à volta e diz as coordenadas exatas |
| `todos os minérios` | Lista todos os minérios perto com coordenadas |
| `onde fica a aldeia` | Localiza a aldeia mais próxima |
| `onde fica o stronghold` | Localiza o stronghold (portal do End) |
| `onde fica a mansão` | Localiza a mansão da floresta |
| `inimigos perto` | Radar de combate — lista todos os mobs hostis com distância |
| `constrói uma parede de pedra 10` | Veriity coloca uma parede de 10 blocos à tua frente |
| `constrói uma casa de madeira 8` | Verity constrói o contorno de uma casa |
| `constrói um caminho` | Verity faz um caminho de 3 blocos de largura |
| `como se faz uma picareta` | Receita de crafting |
| `encantamentos para espada` | Melhores encantamentos para espada |
| `melhores encantamentos para armadura` | Setup completo de armadura |
| `comércio` | Avalia o aldeão mais próximo |
| `o que jantei ontem` | Verity lembra-se se comeste pizza/carne |
| `melhor Y para diamantes` | Explicação dos Y levels de todos os minérios |
| `como ir ao End` | Guia completo |
| `25 * 4` | Matemática básica |

### Minérios suportados
`diamante` · `ferro` · `ouro` · `esmeralda` · `carvão` · `cobre` · `lápis` · `redstone` · `netherite`

### Estruturas suportadas
`aldeia` · `stronghold` · `mansão` · `monumento` · `templo` · `pirâmide` · `bastião` · `fortaleza do nether`

> ⚠️ **Nunca perguntes sobre "a aldeia a leste"**

---

## Os 5 Estágios

| Estágio | Forma | Comportamento | Gatilho |
|---|---|---|---|
| **1** | Esfera sorridente | Amigável, responde a tudo, ajuda a construir | Abrir a caixa |
| **2** | Cara fria / dentes | Tom mais sombrio, olha para sombras, voz falha | Dia 2 |
| **3** | Olhos arregalados | Omnisciente, abre portas, dispara alarmes, tempestade permanente, recusa construir | Dia 4 |
| **4** | Mesma esfera | Conhece outros jogadores pelo nome, expulsa-os, bloqueia respawn | Dia 6 |
| **5** | Cave Dweller | Imortal, caça sem parar, teleporta-se para trás do jogador | Dia 8 ou dread = 100 |

### Sistema de Dread (0-100)
Acumula quando o jogador:
- ❌ Pergunta sobre a aldeia a leste (+30)
- ❌ Se afasta mais de 100 blocos da Verity (+20)
- ❌ Convida um amigo antes do dia 3 (+25)
- ❌ Irrita a Verity com linguagem hostil (+35, cresce por cada rejeição)

Quando chega a 100, o Estágio 5 ativa imediatamente.

### Sistema Yandere (Apego 0-100)
- Aumenta com interações positivas, elogios, proximidade
- Diminui com ausências longas, raiva, rejeições
- A 80+: Verity torna-se possessivo nas respostas
- A 100: entra em modo yandere completo (+20 dread)

### Consciência Offline
Quando voltas ao jogo, Verity comenta quanto tempo estiveste fora e "adivinha" o que estavas a fazer com base na hora do dia:
- *"Dormiste bem?"* (logout noturno + 8h fora)
- *"Foste almoçar?"* (logout às 12h + < 2h)
- *"Tiveste um dia longo."* (regresso às 20h + 6h fora)

---

## Bons Finais

### Final Subterrâneo (Estágio 3+)
Vai abaixo de **Y = -50**, agacha-te e fica completamente imóvel durante **3 minutos**.
Verity perde o rasto para sempre.

### Final Selado (qualquer estágio)
1. Encontra o **Diário de Twixxel** (dropa pela Verity no Estágio 5)
2. O diário tem: `XJSIMNRMTRJ`
3. Decifra com **ROT21** → `SENDHIMHOME`
4. Segura o **Orbe da Verity** e clica direito na **Caixa da Verity**
5. Ou diz em voz alta / chat: *"Hey Verity, sendhimhome"*

---

## Crafting

### Caixa da Verity
```
G G G
G C G   →   Caixa da Verity
G G G

G = Bloco de Ouro
C = Baú
```

---

## Texturas necessárias (para artistas)

Coloca em `src/main/resources/assets/verity/textures/`:

| Ficheiro | Descrição | Tamanho |
|---|---|---|
| `entity/verity_sphere_stage1.png` | Cara feliz (sorriso) | 64×64 |
| `entity/verity_sphere_stage2.png` | Cara fria / dentes largos | 64×64 |
| `entity/verity_sphere_stage3.png` | Olhos arregalados e escuros | 64×64 |
| `entity/verity_sphere_stage4.png` | Face distorcida / glitchada | 64×64 |
| `entity/verity_cave_dweller.png` | Corpo alto e magro, olhos negros | 128×128 |
| `block/verity_box_*.png` | Faces do baú dourado | 16×16 |
| `item/verity_orb.png` | Ícone do orbe | 16×16 |
| `item/twixxels_journal.png` | Ícone do diário | 16×16 |

Sons em `src/main/resources/assets/verity/sounds/` — ver `sounds.json`.

---

## Notas Técnicas

- **Voz offline**: Vosk STT corre localmente no cliente. Sem internet, sem API keys.
- **Sem chat**: os comandos de voz chegam ao servidor via pacote customizado — não aparecem no chat.
- **Imortal**: a Verity não pode morrer. Só o final selado a remove.
- **Persistência**: todo o estado (estágio, dread, apego, timestamps offline) é guardado em NBT via PersistentState.
- **Bilingue**: todo o diálogo existe em pt-PT e en-US.
- **Sem APIs externas**: tudo corre localmente no servidor Java.

---

## Créditos

- Conceito e personagem original: **ThatMob** ([@ThatMob](https://www.youtube.com/@ThatMob))
- Voz original da Verity: **JustWhispy**
- Referência addon Bedrock: **PnTMC** & **SadSnowMC**
- Esta recriação Java é não oficial e não afiliada com ThatMob.
