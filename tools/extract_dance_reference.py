#!/usr/bin/env python3
"""
Script de extracție offline a coregrafiilor de referință pentru aplicația "Dance with ME".

Procesează un fișier video (ex. hora.mp4, gangnam_style.mp4) folosind OpenCV și Google MediaPipe Pose
(33 repere 3D), calculează cele 8 unghiuri articulare 3D identic cu JointAngleCalculator.kt, extrage
coordonatele normalizate [0..1] pentru Ghost Skeleton (ghostFrames), normalizează secvența la exact
600 de cadre (20 secunde @ 30 FPS) și exportă un fișier JSON compatibil cu schema @Serializable din
ReferenceDance.kt, AngleVector.kt și Point3D.kt.

Exemplu de utilizare:
    python extract_dance_reference.py \
        --video path/to/gangnam_style.mp4 \
        --id gangnam_style_04 \
        --title "Gangnam Style" \
        --out ../app/src/main/assets/dances/gangnam_style.json
"""

import argparse
import json
import math
import os
import sys
from typing import Any, Dict, List, Tuple

import cv2
import mediapipe as mp
import numpy as np

# Indici standard MediaPipe Pose (BlazePose 33 landmarks) - identici cu PoseFrame.kt
LEFT_SHOULDER = 11
RIGHT_SHOULDER = 12
LEFT_ELBOW = 13
RIGHT_ELBOW = 14
LEFT_WRIST = 15
RIGHT_WRIST = 16
LEFT_HIP = 23
RIGHT_HIP = 24
LEFT_KNEE = 25
RIGHT_KNEE = 26
LEFT_ANKLE = 27
RIGHT_ANKLE = 28

EPSILON = 1e-6


def calculate_angle_3d(
    a: Tuple[float, float, float],
    b: Tuple[float, float, float],
    c: Tuple[float, float, float],
) -> float:
    """
    Calculează unghiul 3D (în grade, interval [0.0, 180.0]) format în vârful B
    de segmentele BA și BC, folosind exact aceeași formulă a produsului scalar
    ca în JointAngleCalculator.kt.
    """
    ba_x = a[0] - b[0]
    ba_y = a[1] - b[1]
    ba_z = a[2] - b[2]

    bc_x = c[0] - b[0]
    bc_y = c[1] - b[1]
    bc_z = c[2] - b[2]

    dot_product = (ba_x * bc_x) + (ba_y * bc_y) + (ba_z * bc_z)

    mag_ba = math.sqrt((ba_x * ba_x) + (ba_y * ba_y) + (ba_z * ba_z))
    mag_bc = math.sqrt((bc_x * bc_x) + (bc_y * bc_y) + (bc_z * bc_z))

    denominator = mag_ba * mag_bc
    if denominator < EPSILON or math.isnan(denominator) or math.isinf(denominator):
        return 0.0

    cos_theta = max(-1.0, min(1.0, dot_product / denominator))
    angle_rad = math.acos(cos_theta)
    angle_deg = math.degrees(angle_rad)

    if math.isnan(angle_deg) or math.isinf(angle_deg):
        return 0.0

    return float(max(0.0, min(180.0, angle_deg)))


def extract_angles_from_landmarks(
    landmarks: List[Tuple[float, float, float]]
) -> Dict[str, float]:
    """
    Extrage vectorul cu cele 8 unghiuri articulare dintr-o listă de 33 puncte 3D.
    Cheile corespund 1:1 cu proprietățile din AngleVector.kt.
    """
    genunchi_stang = calculate_angle_3d(
        landmarks[LEFT_HIP], landmarks[LEFT_KNEE], landmarks[LEFT_ANKLE]
    )
    genunchi_drept = calculate_angle_3d(
        landmarks[RIGHT_HIP], landmarks[RIGHT_KNEE], landmarks[RIGHT_ANKLE]
    )
    sold_stang = calculate_angle_3d(
        landmarks[LEFT_SHOULDER], landmarks[LEFT_HIP], landmarks[LEFT_KNEE]
    )
    sold_drept = calculate_angle_3d(
        landmarks[RIGHT_SHOULDER], landmarks[RIGHT_HIP], landmarks[RIGHT_KNEE]
    )
    cot_stang = calculate_angle_3d(
        landmarks[LEFT_SHOULDER], landmarks[LEFT_ELBOW], landmarks[LEFT_WRIST]
    )
    cot_drept = calculate_angle_3d(
        landmarks[RIGHT_SHOULDER], landmarks[RIGHT_ELBOW], landmarks[RIGHT_WRIST]
    )
    umar_stang = calculate_angle_3d(
        landmarks[LEFT_HIP], landmarks[LEFT_SHOULDER], landmarks[LEFT_ELBOW]
    )
    umar_drept = calculate_angle_3d(
        landmarks[RIGHT_HIP], landmarks[RIGHT_SHOULDER], landmarks[RIGHT_ELBOW]
    )

    return {
        "genunchiStang": round(genunchi_stang, 2),
        "genunchiDrept": round(genunchi_drept, 2),
        "soldStang": round(sold_stang, 2),
        "soldDrept": round(sold_drept, 2),
        "cotStang": round(cot_stang, 2),
        "cotDrept": round(cot_drept, 2),
        "umarStang": round(umar_stang, 2),
        "umarDrept": round(umar_drept, 2),
    }


def resample_frames(
    raw_frames: List[Dict[str, float]], target_count: int = 600
) -> List[Dict[str, float]]:
    """
    Resamplează / interpolează liniar secvența de AngleVector (8 unghiuri) la exact `target_count` cadre
    (implicit 600 de cadre = 20 secunde @ 30 FPS).
    """
    if not raw_frames:
        raise ValueError("Nu s-au putut extrage cadre valide cu postură din fișierul video.")

    if len(raw_frames) == target_count:
        return raw_frames

    joint_keys = [
        "genunchiStang",
        "genunchiDrept",
        "soldStang",
        "soldDrept",
        "cotStang",
        "cotDrept",
        "umarStang",
        "umarDrept",
    ]

    if len(raw_frames) == 1:
        return [dict(raw_frames[0]) for _ in range(target_count)]

    src_indices = np.linspace(0, len(raw_frames) - 1, num=len(raw_frames))
    dst_indices = np.linspace(0, len(raw_frames) - 1, num=target_count)

    interpolated_per_key: Dict[str, np.ndarray] = {}
    for key in joint_keys:
        series = np.array([frame[key] for frame in raw_frames], dtype=np.float64)
        interpolated_per_key[key] = np.interp(dst_indices, src_indices, series)

    resampled: List[Dict[str, float]] = []
    for i in range(target_count):
        frame_dict = {
            key: round(float(interpolated_per_key[key][i]), 2) for key in joint_keys
        }
        resampled.append(frame_dict)

    return resampled


def resample_ghost_frames(
    raw_ghost_frames: List[List[Dict[str, float]]], target_count: int = 600
) -> List[List[Dict[str, float]]]:
    """
    Resamplează / interpolează liniar secvența de 33 de repere normalizate (ghostFrames)
    la exact `target_count` cadre, rotunjind coordonatele la 3 zecimale pentru un JSON compact.
    """
    if not raw_ghost_frames:
        return []

    if len(raw_ghost_frames) == target_count:
        return raw_ghost_frames

    if len(raw_ghost_frames) == 1:
        return [list(raw_ghost_frames[0]) for _ in range(target_count)]

    num_landmarks = len(raw_ghost_frames[0])
    src_indices = np.linspace(0, len(raw_ghost_frames) - 1, num=len(raw_ghost_frames))
    dst_indices = np.linspace(0, len(raw_ghost_frames) - 1, num=target_count)

    coords = ("x", "y", "z", "visibility")
    # Pre-calculăm interpolarea pentru fiecare dintre cele 33 de repere
    interp_data: List[Dict[str, np.ndarray]] = []
    for lm_idx in range(num_landmarks):
        lm_interp: Dict[str, np.ndarray] = {}
        for coord in coords:
            series = np.array(
                [frame[lm_idx][coord] for frame in raw_ghost_frames], dtype=np.float64
            )
            lm_interp[coord] = np.interp(dst_indices, src_indices, series)
        interp_data.append(lm_interp)

    resampled_ghosts: List[List[Dict[str, float]]] = []
    for i in range(target_count):
        frame_landmarks: List[Dict[str, float]] = []
        for lm_idx in range(num_landmarks):
            frame_landmarks.append(
                {
                    "x": round(float(interp_data[lm_idx]["x"][i]), 3),
                    "y": round(float(interp_data[lm_idx]["y"][i]), 3),
                    "z": round(float(interp_data[lm_idx]["z"][i]), 3),
                    "visibility": round(float(interp_data[lm_idx]["visibility"][i]), 3),
                }
            )
        resampled_ghosts.append(frame_landmarks)

    return resampled_ghosts


def process_video(
    video_path: str,
    dance_id: str,
    title: str,
    output_path: str,
    description: str = "",
    video_asset_name: str = "",
    duration_sec: int = 20,
    bpm: int = 90,
    target_fps: int = 30,
    use_world_landmarks: bool = False,
) -> None:
    if not os.path.isfile(video_path):
        raise FileNotFoundError(f"Fișierul video nu a fost găsit: {video_path}")

    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        raise RuntimeError(f"Nu s-a putut deschide fișierul video: {video_path}")

    total_video_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    video_fps = cap.get(cv2.CAP_PROP_FPS)
    print(
        f"[INFO] Procesare video: {video_path} "
        f"(cadre={total_video_frames}, fps={video_fps:.2f})"
    )

    mp_pose = mp.solutions.pose
    raw_angle_frames: List[Dict[str, float]] = []
    raw_ghost_frames: List[List[Dict[str, float]]] = []

    last_valid_angles: Dict[str, float] = {
        "genunchiStang": 165.0,
        "genunchiDrept": 165.0,
        "soldStang": 170.0,
        "soldDrept": 170.0,
        "cotStang": 150.0,
        "cotDrept": 150.0,
        "umarStang": 90.0,
        "umarDrept": 90.0,
    }
    last_valid_ghost: List[Dict[str, float]] = [
        {"x": 0.5, "y": 0.5, "z": 0.0, "visibility": 1.0} for _ in range(33)
    ]

    frame_idx = 0
    detected_count = 0

    with mp_pose.Pose(
        static_image_mode=False,
        model_complexity=1,
        smooth_landmarks=True,
        min_detection_confidence=0.5,
        min_tracking_confidence=0.5,
    ) as pose:
        while True:
            ret, frame_bgr = cap.read()
            if not ret:
                break

            frame_idx += 1
            frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
            results = pose.process(frame_rgb)

            source_landmarks = (
                results.pose_world_landmarks
                if (use_world_landmarks and results.pose_world_landmarks)
                else results.pose_landmarks
            )
            normalized_landmarks = results.pose_landmarks

            if (
                source_landmarks
                and len(source_landmarks.landmark) >= 33
                and normalized_landmarks
                and len(normalized_landmarks.landmark) >= 33
            ):
                pts_3d: List[Tuple[float, float, float]] = [
                    (float(lm.x), float(lm.y), float(lm.z))
                    for lm in source_landmarks.landmark[:33]
                ]
                angles = extract_angles_from_landmarks(pts_3d)
                last_valid_angles = angles
                raw_angle_frames.append(angles)

                ghost_pts: List[Dict[str, float]] = [
                    {
                        "x": round(max(0.0, min(1.0, float(lm.x))), 3),
                        "y": round(max(0.0, min(1.0, float(lm.y))), 3),
                        "z": round(float(lm.z), 3),
                        "visibility": round(float(getattr(lm, "visibility", 1.0)), 3),
                    }
                    for lm in normalized_landmarks.landmark[:33]
                ]
                last_valid_ghost = ghost_pts
                raw_ghost_frames.append(ghost_pts)

                detected_count += 1
            else:
                # Păstrăm continuitatea temporală dacă un cadru izolat pierde detecția
                if raw_angle_frames:
                    raw_angle_frames.append(dict(last_valid_angles))
                    raw_ghost_frames.append(list(last_valid_ghost))

    cap.release()

    if detected_count == 0:
        raise RuntimeError(
            "MediaPipe Pose nu a detectat nicio persoană în videoclipul furnizat."
        )

    target_frame_count = duration_sec * target_fps
    print(
        f"[INFO] Cadre procesate: {frame_idx} | Cadre cu postură detectată: {detected_count} | "
        f"Resamplare la {target_frame_count} cadre ({duration_sec}s @ {target_fps} FPS)..."
    )

    normalized_frames = resample_frames(raw_angle_frames, target_count=target_frame_count)
    normalized_ghost_frames = resample_ghost_frames(
        raw_ghost_frames, target_count=target_frame_count
    )

    effective_video_asset = (
        video_asset_name if video_asset_name else os.path.basename(video_path)
    )

    reference_dance_payload: Dict[str, Any] = {
        "id": dance_id,
        "title": title,
        "durationSec": duration_sec,
        "bpm": bpm,
        "description": description,
        "videoAssetName": effective_video_asset,
        "frames": normalized_frames,
        "ghostFrames": normalized_ghost_frames,
    }

    out_dir = os.path.dirname(os.path.abspath(output_path))
    if out_dir and not os.path.exists(out_dir):
        os.makedirs(out_dir, exist_ok=True)

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(reference_dance_payload, f, ensure_ascii=False, indent=2)

    print(
        f"[SUCCESS] Coregrafia de referință ({len(normalized_frames)} frames [8 unghiuri] + "
        f"{len(normalized_ghost_frames)} ghostFrames) a fost salvată în: {output_path}"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Extrage coregrafia de referință 3D (8 unghiuri + 33 repere Ghost @ 30 FPS) dintr-un video."
    )
    parser.add_argument(
        "--video",
        required=True,
        help="Calea către fișierul video sursă (ex. path/to/hora.mp4)",
    )
    parser.add_argument(
        "--id",
        required=True,
        help="ID-ul unic al dansului (ex. hora_simpla_01)",
    )
    parser.add_argument(
        "--title",
        required=True,
        help='Titlul afișat în aplicație (ex. "Hora Simplă")',
    )
    parser.add_argument(
        "--out",
        required=True,
        help="Calea fișierului JSON de ieșire (ex. ../app/src/main/assets/dances/hora_simpla.json)",
    )
    parser.add_argument(
        "--description",
        default="",
        help="Descrierea pas cu pas a dansului afișată în TutorialScreen.",
    )
    parser.add_argument(
        "--video-asset",
        default="",
        help="Numele fișierului video din assets/videos/ (implicit: numele de bază din --video).",
    )
    parser.add_argument(
        "--duration",
        type=int,
        default=20,
        help="Durata țintă a drill-ului în secunde (implicit: 20)",
    )
    parser.add_argument(
        "--bpm",
        type=int,
        default=90,
        help="Tempo-ul (BPM - bătăi pe minut) al coregrafiei (implicit: 90)",
    )
    parser.add_argument(
        "--fps",
        type=int,
        default=30,
        help="Numărul de cadre pe secundă țintă (implicit: 30 -> 600 cadre pentru 20s)",
    )
    parser.add_argument(
        "--world-landmarks",
        action="store_true",
        help="Folosește pose_world_landmarks (coordonate 3D în metri) pentru calculul unghiurilor.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        process_video(
            video_path=args.video,
            dance_id=args.id,
            title=args.title,
            output_path=args.out,
            description=args.description,
            video_asset_name=args.video_asset,
            duration_sec=args.duration,
            bpm=args.bpm,
            target_fps=args.fps,
            use_world_landmarks=args.world_landmarks,
        )
        return 0
    except Exception as exc:
        print(f"[EROARE] {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
