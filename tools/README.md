# Utilitar Extragere Coregrafii de Referință (`Dance with ME`)

Acest director conține scriptul Python [`extract_dance_reference.py`](extract_dance_reference.py) care procesează un videoclip cu un dansator (ex. Hora Simplă, Sârba, Makedoneasca) folosind **OpenCV** și **Google MediaPipe Pose (33 repere 3D)**, calculează cele **6 unghiuri articulare 3D** folosind aceeași formulă a produsului scalar ca în `JointAngleCalculator.kt` și exportă un fișier JSON normalizat la **600 de cadre** (20 secunde @ 30 FPS), gata de inclus în `app/src/main/assets/dances/`.

---

## 1. Instalare Dependențe

Asigură-te că ai **Python 3.9 – 3.11** instalat, apoi rulează din directorul `tools/`:

```bash
cd tools
python -m venv .venv
# Pe Windows (PowerShell):
.\.venv\Scripts\Activate.ps1
# Pe macOS / Linux:
# source .venv/bin/activate

pip install -r requirements.txt
```

---

## 2. Rulare Script pentru Generarea Fișierelor JSON

Rulează scriptul specificând videoclipul sursă (`--video`), ID-ul coregrafiei (`--id`), titlul afișat în meniu (`--title`) și calea de ieșire în `assets/dances/` (`--out`):

### Hora Simplă
```bash
python extract_dance_reference.py \
    --video path/to/hora.mp4 \
    --id hora_simpla_01 \
    --title "Hora Simplă" \
    --out ../app/src/main/assets/dances/hora_simpla.json
```

### Sârba
```bash
python extract_dance_reference.py \
    --video path/to/sarba.mp4 \
    --id sarba_01 \
    --title "Sârba" \
    --out ../app/src/main/assets/dances/sarba.json
```

### Makedoneasca
```bash
python extract_dance_reference.py \
    --video path/to/makedoneasca.mp4 \
    --id makedoneasca_01 \
    --title "Makedoneasca" \
    --out ../app/src/main/assets/dances/makedoneasca.json
```

---

## 3. Formatul JSON Generat

Fișierul exportat respectă 100% schema `@Serializable` din `ReferenceDance.kt` și `AngleVector.kt` și este încărcat automat la pornirea aplicației de către `DanceRepository.kt`:

```json
{
  "id": "hora_simpla_01",
  "title": "Hora Simplă",
  "durationSec": 20,
  "frames": [
    {
      "genunchiStang": 158.42,
      "genunchiDrept": 157.95,
      "soldStang": 164.10,
      "soldDrept": 163.88,
      "cotStang": 112.50,
      "cotDrept": 111.80
    }
  ]
}
```
