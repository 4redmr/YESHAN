#!/usr/bin/env python3
"""
광고성 리뷰 의심 점수 - ad-review-filter.md의 R1~R3를 코드로 옮긴 1단계.

R4(정형화된 구조)/R5(문구 유사도)/R6(업로드 패턴)은 여러 리뷰·작성자 이력을
비교해야 해서 리뷰 1건 단위로는 계산할 수 없다. 데이터가 쌓이면 추가한다.
"""

import re

# 2.1 명시적 키워드/해시태그
EXPLICIT_KEYWORDS = [
    "협찬", "체험단", "원고료", "제공받았습니다", "제공받은", "서비스로 받음",
    "무료로 제공", "업체로부터 제공", "#협찬", "#체험단", "#광고", "#유료광고",
]

# 2.2 과장된 감탄사 (예시 목록, ad-review-filter.md 참고)
EXAGGERATED_WORDS = [
    "인생맛집", "레전드", "미쳤다", "꿀맛", "존맛", "최고예요",
]

SENTENCE_SPLIT = re.compile(r"[.!?\n]+")
EXCLAIM_OR_EMOJI = re.compile(r"[!ㅠㅜ❤️😍😋👍]")


def score_review(text: str) -> tuple[int, list[str]]:
    """리뷰 텍스트 1건의 광고 의심 점수와 근거를 돌려준다."""
    score = 0
    reasons = []

    if any(keyword in text for keyword in EXPLICIT_KEYWORDS):
        score += 40
        reasons.append("R1: 명시적 협찬/체험단 키워드 포함")

    exaggerated_count = sum(text.count(word) for word in EXAGGERATED_WORDS)
    if exaggerated_count >= 2:
        score += 15
        reasons.append(f"R2: 과장 감탄사 {exaggerated_count}회 등장")

    sentences = [s for s in SENTENCE_SPLIT.split(text) if s.strip()]
    marks = len(EXCLAIM_OR_EMOJI.findall(text))
    if sentences and marks / len(sentences) >= 2:
        score += 10
        reasons.append(f"R3: 느낌표·이모지 밀도 높음(문장당 {marks / len(sentences):.1f}개)")

    return score, reasons


def classify(score: int) -> str:
    if score >= 50:
        return "광고 의심"
    if score >= 30:
        return "협찬 가능성 있음"
    return "일반 리뷰"
