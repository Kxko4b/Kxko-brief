/* Editable content. Replace these with your own questions and cards.
   study: flashcards (front = question, back = answer)
   cycling: multiple choice (answer = index of the correct option, starting at 0) */
window.KB_CONTENT = {
  study: [
    { id: 'tyre', topic: 'Bike care', front: 'Rough pressure range for 25 mm road tyres?', back: 'About 80–100 psi (5.5–7 bar), depending on rider weight and surface.' },
    { id: 'abc', topic: 'Bike care', front: 'What does the ABC check cover?', back: 'Air (tyre pressure), Brakes, Chain and cranks.' },
    { id: 'cadence', topic: 'Training', front: 'Cadence most riders find efficient?', back: 'Roughly 80–100 rpm.' },
    { id: 'zone2', topic: 'Training', front: 'What is Zone 2 training?', back: 'Easy, conversational effort, roughly 60–70 % of max heart rate.' },
    { id: 'fluids', topic: 'Nutrition', front: 'Typical fluid intake on a long ride?', back: 'Roughly 500–1000 ml per hour, more in heat or hard effort.' },
    { id: 'carbs', topic: 'Nutrition', front: 'Carbs per hour on rides over 90 minutes?', back: 'Often around 30–60 g per hour.' },
    { id: 'bonk', topic: 'Nutrition', front: "What is 'bonking'?", back: 'Sudden exhaustion from depleted glycogen stores.' },
    { id: 'helmet', topic: 'Safety', front: 'How should a helmet fit?', back: 'Snug and level, about two finger-widths above the eyebrows.' },
    { id: 'left', topic: 'Safety', front: 'Hand signal for turning left?', back: 'Left arm straight out to the side.' },
    { id: 'stop', topic: 'Safety', front: 'Hand signal for stopping?', back: 'Left arm pointing down, palm facing back.' }
  ],
  cycling: [
    { id: 'q1', q: 'Which gear is easiest to pedal on a hill?', options: ['Big chainring, big cog', 'Small chainring, small cog', 'Small chainring, big cog', 'Big chainring, small cog'], answer: 2 },
    { id: 'q2', q: 'What should you check first before a ride?', options: ['Tyre pressure and brakes', 'The weather app', 'Your playlist', 'Your route on social media'], answer: 0 },
    { id: 'q3', q: 'What does a flat-looking front tyre usually mean?', options: ['It is inflated too hard', 'It needs more air', 'The chain is loose', 'The brakes are binding'], answer: 1 },
    { id: 'q4', q: 'When braking on a bike, which brake does most of the work?', options: ['The rear brake', 'The front brake', 'Both equally', 'Neither, you coast'], answer: 1 },
    { id: 'q5', q: 'What is a good way to be seen at night?', options: ['Dark clothing', 'Front white and rear red lights', 'Headphones', 'Riding on the pavement'], answer: 1 }
  ],

  /* Access control. Every module key matches a tab (dashboard, ride, study, quiz, rides, settings, admin).
     "admin" modules are only visible to users with role "admin".
     userModules: what a normal user may see. Add keys here to grant access. */
  access: {
    userModules: ['dashboard', 'ride', 'study', 'quiz', 'rides', 'settings'],
    adminModules: ['admin']
  }
};
