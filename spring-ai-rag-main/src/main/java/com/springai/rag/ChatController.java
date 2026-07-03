    package com.springai.rag;

    import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
    import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
    import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
    import org.springframework.ai.chat.messages.AssistantMessage;
    import org.springframework.ai.chat.model.ChatResponse;
    import org.springframework.ai.chat.model.Generation;
    import org.springframework.ai.chat.prompt.Prompt;
    import org.springframework.ai.document.Document;
    import org.springframework.ai.ollama.OllamaChatModel;
    import org.springframework.ai.vectorstore.SearchRequest;
    import org.springframework.ai.vectorstore.VectorStore;
    import org.springframework.web.bind.annotation.*;

    import java.util.List;
    import java.util.Map;
    import java.util.stream.Collectors;

    @RestController
    @RequestMapping("/api/chat")
    public class ChatController {

        private final OllamaChatModel ollamaChatModel;
        private final VectorStore vectorStore;
        private final QuestionAnswerAdvisor advisor;

        public ChatController(OllamaChatModel ollamaChatModel, VectorStore vectorStore) {
            this.ollamaChatModel = ollamaChatModel;
            this.vectorStore = vectorStore;

            // Reuse advisor for all requests
            this.advisor = QuestionAnswerAdvisor.builder(vectorStore)
                    .userTextAdvise(
                            "\nYou are a highly knowledgeable assistant. Use the provided context to answer the user's question accurately and professionally.\n" +
                                    "Summarize the relevant information clearly and concisely.\n" +
                                    "Do not invent information that is not present in the context.\n" +
                                    "If the context does not contain the answer, politely reply: 'I’m sorry, I do not have enough information to answer that question.'\n" +
                                    "Maintain a helpful, professional, and neutral tone.\n"
                    )
                    .build();
        }



        @PostMapping
        public Map<String, Object> chat(@RequestBody String message) {

            long startTime = System.currentTimeMillis();

            SearchRequest searchRequest = SearchRequest.builder()
                    .query(message)
                    .topK(3) // fewer docs = faster
                    .build();

            List<Document> docs = vectorStore.similaritySearch(searchRequest);

            if (docs == null || docs.isEmpty()) {
                return Map.of("error", "Sorry, no relevant information was found in the knowledge base.");
            }

            // 2️⃣ Limit context size (optional: take only first 3 docs)
            String contextText = docs.stream()
                    .limit(3)
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));

            // 3️⃣ Build AdvisedRequest
            AdvisedRequest advisedRequest = AdvisedRequest.builder()
                    .userText(message)
                    .userParams(Map.of("question_answer_context", contextText))
                    .adviseContext(Map.of("qa_retrieved_documents", docs))
                    .chatModel(ollamaChatModel)
                    .build();

            // 4️⃣ Advisor call
            AdvisedResponse advisedResponse = advisor.aroundCall(advisedRequest, (req) -> {
                ChatResponse chatResponse = ollamaChatModel.call(
                        new Prompt(req.userText() + "\n\nContext:\n" + contextText)
                );
                return AdvisedResponse.builder()
                        .response(chatResponse)
                        .adviseContext(req.adviseContext())
                        .build();
            });

            // 5️⃣ Extract LLM answer safely
            String answer = "⚠️ Sorry, I could not generate a valid response.";

            if (advisedResponse.response() != null &&
                    advisedResponse.response().getResult() != null &&
                    advisedResponse.response().getResult().getOutput() != null ) {

                Generation generation = advisedResponse.response().getResult();

                if (generation != null && generation.getOutput() != null) {
                    AssistantMessage assistantMessage = generation.getOutput();
                    answer = assistantMessage.getText(); // actual LLM reply
                }
            }

            long endTime = System.currentTimeMillis();
            System.out.println("Chat processing time: " + (endTime - startTime) + " ms");

            // 6️⃣ Return structured JSON
            return Map.of(
                    "answer", answer,
                    "sources", advisedResponse.adviseContext().getOrDefault("qa_retrieved_documents", List.of()),
                    "processingTimeMs", (endTime - startTime)
            );
        }
    }
