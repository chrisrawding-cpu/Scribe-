package com.scribe.app.ai

import com.scribe.app.data.Template

object BuiltInTemplates {
    val all = listOf(
        Template(
            "auto", "Auto (best fit)", "Detects the type of conversation and structures notes to suit",
            """Work out what kind of recording this is (meeting, call, lecture, interview, voice memo, etc.) and
structure the summary the way an expert note-taker would for that kind of recording. Start the summary with
a one-line TL;DR in bold.""", true,
        ),
        Template(
            "meeting", "Meeting minutes", "Agenda, discussion, decisions, owners",
            """Write formal meeting minutes. Summary sections (markdown headings): **TL;DR**, ## Attendees
(names or speaker labels and roles if known), ## Agenda & discussion (one sub-section per topic with what
was said and by whom), ## Decisions, ## Risks & blockers, ## Next steps. Be specific: keep numbers, dates,
names and commitments exactly as spoken.""", true,
        ),
        Template(
            "one_on_one", "1:1 / check-in", "Wins, concerns, feedback, growth",
            """Summarise this 1:1. Sections: **TL;DR**, ## Updates & wins, ## Concerns / blockers, ## Feedback
given and received, ## Career & development, ## Agreed follow-ups. Keep a supportive, factual tone.""", true,
        ),
        Template(
            "sales", "Sales / customer call", "BANT, pain points, objections, next steps",
            """Summarise this customer/sales conversation. Sections: **TL;DR**, ## Customer & context,
## Pain points & goals, ## Budget, authority, need, timeline (BANT), ## Products / pricing discussed,
## Objections & how they were handled, ## Competitors mentioned, ## Buying signals, ## Next steps (with dates).
Include a suggested follow-up email at the end under ## Draft follow-up email.""", true,
        ),
        Template(
            "interview", "Interview", "Candidate or research interview write-up",
            """Write up this interview. Sections: **TL;DR**, ## Background of interviewee, ## Q&A highlights
(question → answer summary, with notable quotes in italics), ## Strengths, ## Concerns / gaps,
## Overall assessment.""", true,
        ),
        Template(
            "lecture", "Lecture / training", "Study notes, concepts, definitions, quiz",
            """Turn this into study notes. Sections: **TL;DR**, ## Key concepts (with clear explanations),
## Definitions & formulas, ## Examples, ## Things to remember, ## Self-test questions (5 questions with answers).""", true,
        ),
        Template(
            "brainstorm", "Brainstorm / workshop", "Ideas clustered by theme, pros/cons",
            """Summarise this brainstorm. Sections: **TL;DR**, ## Problem being solved, ## Ideas grouped by theme
(each with pros and cons), ## Most promising ideas and why, ## Open questions, ## Next experiments.""", true,
        ),
        Template(
            "standup", "Stand-up / project update", "Per-person yesterday/today/blockers",
            """Summarise this stand-up. For each person: ## Name — Done, Doing next, Blockers. Then ## Team-level
risks and ## Follow-ups.""", true,
        ),
        Template(
            "medical", "Doctor / consultation", "Symptoms, advice, medication, follow-up",
            """Summarise this consultation for the patient's personal records. Sections: **TL;DR**, ## Reason for
visit, ## Symptoms & history discussed, ## Examination / test results, ## Diagnosis or assessment,
## Treatment & medication (with doses exactly as stated), ## Advice given, ## Follow-up appointments.
Do not add medical advice that was not stated in the recording.""", true,
        ),
        Template(
            "memo", "Voice memo / thoughts", "Clean up rambling notes into clear ideas",
            """This is a personal voice memo. Rewrite the speaker's thoughts as clear, well-organised notes:
**TL;DR**, ## Main ideas, ## Details, ## To-dos. Keep the speaker's voice and intent; remove filler.""", true,
        ),
    )

    fun find(id: String) = all.firstOrNull { it.id == id }
}
